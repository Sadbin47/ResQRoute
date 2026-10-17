package com.resqroute.data.p2p

import com.resqroute.data.dataset.JsonParser
import com.resqroute.data.dataset.JsonSyntaxException
import com.resqroute.data.dataset.JsonValue
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.HazardMutation
import com.resqroute.domain.hazard.HazardReason as Reason
import com.resqroute.domain.hazard.ObservationValidation
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.RoadObservation
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.sync.PeerMessage
import com.resqroute.domain.sync.PeerProtocol
import java.nio.charset.StandardCharsets

/** Outcome of decoding one payload. */
sealed interface DecodeResult {

    data class Ok(val message: PeerMessage) : DecodeResult

    /** Structurally invalid: bad JSON, missing fields, invalid ids. */
    data class Malformed(val reason: String) : DecodeResult

    /** Well-formed but unsupported: wrong version, unknown type or reason. */
    data class Unsupported(val reason: String) : DecodeResult

    /** Larger than the application payload limit. */
    data class TooLarge(val bytes: Int) : DecodeResult
}

/**
 * Versioned JSON codec for the peer protocol.
 *
 * Validation is deliberately paranoid (blueprint section 11, "Wire validation"):
 * payload size, UTF-8/JSON validity, supported version and type, bounded
 * strings, valid identifiers, supported reason codes and bounded numbers. A
 * rejected payload never reaches the hazard repository, so a malformed message
 * cannot mutate durable state.
 *
 * ## The v2 event schema (XPH-78)
 *
 * An event carries the two observation axes explicitly, as their own fields, in addition to the
 * provenance reason:
 *
 * ```json
 * {"id":"evt-…","device":"device-…","dataset":"<sha256>","incident":"incident-a",
 *  "segment":42,"reason":"WATERLOGGED","level":"KNEE","passability":"CAUTION",
 *  "occurredAt":1700000000000}
 * ```
 *
 * Three rules make that safe to receive:
 *
 *  * **The receiver derives, it does not trust.** `reason` is provenance; whether a road is
 *    excluded is derived from the axes by the receiving device, through the same aggregation it
 *    applies to its own reports.
 *  * **Nothing about the sender's device state travels.** The event carries the reporter's
 *    statement and its identity, and nothing else: in particular **not**
 *    `receivedAtEpochMillis`, which is this device's own receipt stamp and the only freshness
 *    input no peer can influence (ADR 0005 §6.2), and **not** any position or fix accuracy
 *    (XPH-90 — a report says *which road*, never where the reporter stood).
 *  * **A reason-only event still means what v1 meant.** See [decodeObservation].
 */
object PeerMessageCodec {

    private val SAFE_ID = Regex("^[A-Za-z0-9._:-]+$")

    fun encode(message: PeerMessage): ByteArray {
        val json = when (message) {
            is PeerMessage.Hello -> buildString {
                append("{\"t\":\"hello\",\"v\":").append(message.protocolVersion)
                append(",\"device\":").append(quote(message.deviceId.value))
                append(",\"dataset\":").append(quote(message.datasetHash))
                append(",\"incident\":").append(quote(message.incidentId))
                append('}')
            }
            is PeerMessage.EventBatch -> buildString {
                append("{\"t\":\"events\",\"v\":").append(PeerProtocol.VERSION)
                append(",\"events\":[")
                message.events.forEachIndexed { index, event ->
                    if (index > 0) append(',')
                    append("{\"id\":").append(quote(event.eventId.value))
                    append(",\"device\":").append(quote(event.originDeviceId.value))
                    append(",\"dataset\":").append(quote(event.context.dataset.contentHash))
                    append(",\"incident\":").append(quote(event.context.incidentId))
                    append(",\"segment\":").append(event.segmentId.value)
                    append(",\"reason\":").append(quote(event.reason.code))
                    // The two axes travel explicitly and always (XPH-78). A receiving device
                    // derives its own excluded set from them and must not trust our reason to imply
                    // one - and omitting them when they merely happen to match the legacy meaning
                    // would put a safety-relevant default into normal traffic.
                    append(",\"level\":").append(quote(event.observation.waterLevel.code))
                    append(",\"passability\":").append(quote(event.observation.passability.code))
                    append(",\"occurredAt\":").append(event.occurredAtEpochMillis)
                    append('}')
                }
                append("]}")
            }
            is PeerMessage.Ack -> buildString {
                append("{\"t\":\"ack\",\"v\":").append(PeerProtocol.VERSION)
                append(",\"id\":").append(quote(message.eventId.value))
                append('}')
            }
            PeerMessage.SyncComplete -> "{\"t\":\"sync_complete\",\"v\":${PeerProtocol.VERSION}}"
        }
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size > PeerProtocol.MAX_PAYLOAD_BYTES) return DecodeResult.TooLarge(bytes.size)

        val root = try {
            JsonParser.parse(String(bytes, StandardCharsets.UTF_8))
        } catch (error: JsonSyntaxException) {
            return DecodeResult.Malformed("invalid JSON: ${error.message}")
        } as? JsonValue.ObjectValue ?: return DecodeResult.Malformed("payload root must be an object")

        val version = root.number("v")?.toInt() ?: return DecodeResult.Malformed("missing protocol version")
        if (version != PeerProtocol.VERSION) {
            return DecodeResult.Unsupported("unsupported protocol version $version")
        }
        val type = root.string("t") ?: return DecodeResult.Malformed("missing message type")

        return when (type) {
            "hello" -> decodeHello(root)
            "events" -> decodeEvents(root)
            "ack" -> decodeAck(root)
            "sync_complete" -> DecodeResult.Ok(PeerMessage.SyncComplete)
            else -> DecodeResult.Unsupported("unknown message type '$type'")
        }
    }

    private fun decodeHello(root: JsonValue.ObjectValue): DecodeResult {
        val device = root.string("device") ?: return DecodeResult.Malformed("hello is missing 'device'")
        idProblem(device)?.let { return DecodeResult.Malformed("hello device: $it") }
        val dataset = root.string("dataset") ?: return DecodeResult.Malformed("hello is missing 'dataset'")
        val incident = root.string("incident") ?: return DecodeResult.Malformed("hello is missing 'incident'")
        textProblem(dataset, "dataset")?.let { return DecodeResult.Malformed(it) }
        textProblem(incident, "incident")?.let { return DecodeResult.Malformed(it) }
        return DecodeResult.Ok(
            PeerMessage.Hello(
                protocolVersion = PeerProtocol.VERSION,
                deviceId = DeviceId(device),
                datasetHash = dataset,
                incidentId = incident,
            ),
        )
    }

    private fun decodeEvents(root: JsonValue.ObjectValue): DecodeResult {
        val array = root.values["events"] as? JsonValue.ArrayValue
            ?: return DecodeResult.Malformed("events message is missing 'events'")
        if (array.values.isEmpty()) return DecodeResult.Malformed("events batch is empty")
        if (array.values.size > PeerProtocol.MAX_EVENTS_PER_BATCH) {
            return DecodeResult.Malformed("events batch exceeds ${PeerProtocol.MAX_EVENTS_PER_BATCH}")
        }

        val events = ArrayList<HazardMutation>(array.values.size)
        for ((index, value) in array.values.withIndex()) {
            val event = value as? JsonValue.ObjectValue
                ?: return DecodeResult.Malformed("event $index is not an object")

            val eventId = event.string("id") ?: return DecodeResult.Malformed("event $index is missing 'id'")
            idProblem(eventId)?.let { return DecodeResult.Malformed("event $index id: $it") }
            val device = event.string("device") ?: return DecodeResult.Malformed("event $index is missing 'device'")
            idProblem(device)?.let { return DecodeResult.Malformed("event $index device: $it") }
            val dataset = event.string("dataset") ?: return DecodeResult.Malformed("event $index is missing 'dataset'")
            val incident = event.string("incident") ?: return DecodeResult.Malformed("event $index is missing 'incident'")
            textProblem(dataset, "dataset")?.let { return DecodeResult.Malformed("event $index: $it") }
            textProblem(incident, "incident")?.let { return DecodeResult.Malformed("event $index: $it") }

            val segment = event.number("segment")?.toInt()
                ?: return DecodeResult.Malformed("event $index is missing 'segment'")
            if (segment < 0) return DecodeResult.Malformed("event $index has a negative segment")
            val occurredAt = event.number("occurredAt")?.toLong()
                ?: return DecodeResult.Malformed("event $index is missing 'occurredAt'")

            val reasonCode = event.string("reason") ?: return DecodeResult.Malformed("event $index is missing 'reason'")
            val reason = Reason.fromCode(reasonCode)
                ?: return DecodeResult.Unsupported("unknown reason '$reasonCode'")

            val observation = when (val outcome = decodeObservation(event, index, reason)) {
                is ObservationDecode.Decoded -> outcome.observation
                is ObservationDecode.Refused -> return outcome.result
            }

            events += HazardMutation(
                eventId = EventId(eventId),
                originDeviceId = DeviceId(device),
                context = HazardContext(
                    // The wire carries the dataset hash; it is the identity that
                    // matters, and the repository compares hashes, not versions.
                    dataset = DatasetIdentity(dataset, PeerProtocol.VERSION),
                    incidentId = incident,
                ),
                segmentId = SegmentId(segment),
                reason = reason,
                occurredAtEpochMillis = occurredAt,
                observation = observation,
            )
        }
        return DecodeResult.Ok(PeerMessage.EventBatch(events))
    }

    /** Either the observation an event states, or the refusal that replaces the whole payload. */
    private sealed interface ObservationDecode {
        data class Decoded(val observation: RoadObservation) : ObservationDecode
        data class Refused(val result: DecodeResult) : ObservationDecode
    }

    /**
     * Reads the two observation axes (XPH-78), or the documented default when none is stated.
     *
     * Four outcomes, each deliberate:
     *
     *  * **Neither field present** — the v1 event shape: a reason and nothing else. It maps through
     *    [RoadObservation.fromLegacy], the *same* mapping that reads a v1 stored row, so a
     *    reason-only message cannot acquire an interpretation that storage would not have produced
     *    (ADR 0001 D2). That mapping is the documented default, and it is the **fail-closed**
     *    direction: a legacy reason implies a hard exclusion.
     *  * **Exactly one field present** — refused. The axes are separate observations and the app
     *    never infers one from the other (ADR 0001 D1), so a half-stated observation has no
     *    documented meaning. Reading it as the legacy default would be inventing the missing axis.
     *  * **A field present but not a string** — refused. Presence is checked separately from type on
     *    purpose: a numeric `level` must not be read as an *absent* level, which would silently
     *    acquire the legacy default's meaning.
     *  * **Present and readable** — validated by [RoadObservation.create], so R1 (states nothing) and
     *    R2 (above-waist reported passable) are refused here too. The codec cannot construct a
     *    contradictory observation at all, and normalising one is exactly what ADR 0002 forbids.
     *
     * An unrecognised code is refused as `Unsupported` — the sender meant something real, we simply
     * do not have it — while a structurally impossible event is `Malformed`. Both refuse the whole
     * payload: a batch is never partially trusted.
     */
    private fun decodeObservation(
        event: JsonValue.ObjectValue,
        index: Int,
        reason: Reason,
    ): ObservationDecode {
        val hasLevel = event.values.containsKey(PeerProtocol.FIELD_LEVEL)
        val hasPassability = event.values.containsKey(PeerProtocol.FIELD_PASSABILITY)

        if (!hasLevel && !hasPassability) {
            return ObservationDecode.Decoded(RoadObservation.fromLegacy(reason))
        }
        if (hasLevel != hasPassability) {
            return ObservationDecode.Refused(
                DecodeResult.Malformed("event $index states one observation axis without the other"),
            )
        }

        val levelCode = event.string(PeerProtocol.FIELD_LEVEL)
            ?: return ObservationDecode.Refused(
                DecodeResult.Malformed("event $index '${PeerProtocol.FIELD_LEVEL}' must be a string"),
            )
        val passabilityCode = event.string(PeerProtocol.FIELD_PASSABILITY)
            ?: return ObservationDecode.Refused(
                DecodeResult.Malformed("event $index '${PeerProtocol.FIELD_PASSABILITY}' must be a string"),
            )

        val level = WaterLevelCategory.fromCode(levelCode)
            ?: return ObservationDecode.Refused(DecodeResult.Unsupported("unknown level '$levelCode'"))
        val passability = PassabilityStatus.fromCode(passabilityCode)
            ?: return ObservationDecode.Refused(
                DecodeResult.Unsupported("unknown passability '$passabilityCode'"),
            )

        return when (val validation = RoadObservation.create(reason, level, passability)) {
            is ObservationValidation.Valid -> ObservationDecode.Decoded(validation.observation)
            is ObservationValidation.Invalid -> ObservationDecode.Refused(
                DecodeResult.Malformed("event $index: ${validation.problem}"),
            )
        }
    }

    private fun decodeAck(root: JsonValue.ObjectValue): DecodeResult {
        val id = root.string("id") ?: return DecodeResult.Malformed("ack is missing 'id'")
        idProblem(id)?.let { return DecodeResult.Malformed("ack id: $it") }
        return DecodeResult.Ok(PeerMessage.Ack(EventId(id)))
    }

    private fun idProblem(value: String): String? {
        if (value.length !in PeerProtocol.MIN_ID_LENGTH..PeerProtocol.MAX_ID_LENGTH) {
            return "id length must be within ${PeerProtocol.MIN_ID_LENGTH}..${PeerProtocol.MAX_ID_LENGTH}"
        }
        if (!SAFE_ID.matches(value)) return "id contains unsupported characters"
        return null
    }

    private fun textProblem(value: String, label: String): String? {
        if (value.isBlank() || value.length > PeerProtocol.MAX_ID_LENGTH * 2) {
            return "$label is blank or too long"
        }
        return null
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun JsonValue.ObjectValue.string(name: String): String? =
        (values[name] as? JsonValue.StringValue)?.value

    private fun JsonValue.ObjectValue.number(name: String): Double? =
        (values[name] as? JsonValue.NumberValue)?.value
}
