package com.resqroute.p2p

import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.SegmentId
import com.resqroute.data.dataset.JsonParser
import com.resqroute.data.dataset.JsonValue
import com.resqroute.data.p2p.DecodeResult
import com.resqroute.data.p2p.PeerMessageCodec
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.HazardMutation
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservationValidation
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.RoadObservation
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.sync.PeerMessage
import com.resqroute.domain.sync.PeerProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire validation and round-trip fidelity (acceptance criteria 5 and 8). */
class PeerMessageCodecTest {

    private val hash = "a".repeat(64)

    /**
     * The dataset **format** version is not on the wire (the hash is the identity), so a decoder
     * cannot know the sender's and substitutes the protocol version. The fixture is built the same
     * way so that round-trip equality compares what actually travels - and
     * [theSenderDatasetFormatVersionIsNotAWireField] pins that design rather than leaving it implied.
     */
    private val context = HazardContext(DatasetIdentity(hash, PeerProtocol.VERSION), "incident-a")

    private fun mutation(
        id: String = "evt-00000001",
        device: String = "device-fixed-0001",
        segment: Int = 42,
        reason: HazardReason = HazardReason.WATERLOGGED,
        occurredAt: Long = 1_700_000_000_000L,
    ) = HazardMutation(
        eventId = EventId(id),
        originDeviceId = DeviceId(device),
        context = context,
        segmentId = SegmentId(segment),
        reason = reason,
        occurredAtEpochMillis = occurredAt,
    )

    private fun decode(bytes: ByteArray): DecodeResult = PeerMessageCodec.decode(bytes)

    @Test
    fun helloRoundTrips() {
        val hello = PeerMessage.Hello(PeerProtocol.VERSION, DeviceId("device-fixed-0001"), hash, "incident-a")
        val decoded = decode(PeerMessageCodec.encode(hello))
        assertTrue(decoded is DecodeResult.Ok)
        assertEquals(hello, (decoded as DecodeResult.Ok).message)
    }

    @Test
    fun eventBatchRoundTripsEveryField() {
        val events = listOf(
            mutation(segment = 1, reason = HazardReason.WATERLOGGED),
            mutation(id = "evt-00000002", device = "peer-device-0009", segment = 99, reason = HazardReason.BLOCKED),
        )
        val decoded = decode(PeerMessageCodec.encode(PeerMessage.EventBatch(events)))
        assertTrue(decoded is DecodeResult.Ok)
        val batch = (decoded as DecodeResult.Ok).message as PeerMessage.EventBatch
        assertEquals(events, batch.events)
    }

    @Test
    fun ackAndSyncCompleteRoundTrip() {
        assertEquals(
            PeerMessage.Ack(EventId("evt-00000001")),
            (decode(PeerMessageCodec.encode(PeerMessage.Ack(EventId("evt-00000001")))) as DecodeResult.Ok).message,
        )
        assertEquals(
            PeerMessage.SyncComplete,
            (decode(PeerMessageCodec.encode(PeerMessage.SyncComplete)) as DecodeResult.Ok).message,
        )
    }

    @Test
    fun oversizedPayloadIsRejected() {
        val bytes = ByteArray(PeerProtocol.MAX_PAYLOAD_BYTES + 1)
        assertTrue(decode(bytes) is DecodeResult.TooLarge)
    }

    @Test
    fun malformedJsonIsRejected() {
        assertTrue(decode("{not json".toByteArray()) is DecodeResult.Malformed)
        assertTrue(decode("[]".toByteArray()) is DecodeResult.Malformed)
        assertTrue(decode("{}".toByteArray()) is DecodeResult.Malformed)
    }

    @Test
    fun unsupportedVersionAndTypeAreRejected() {
        assertTrue(decode("""{"t":"hello","v":99,"device":"device-fixed-0001","dataset":"${hash}","incident":"x"}""".toByteArray()) is DecodeResult.Unsupported)
        assertTrue(decode("""{"t":"nope","v":${PeerProtocol.VERSION}}""".toByteArray()) is DecodeResult.Unsupported)
    }

    @Test
    fun unknownReasonIsRejected() {
        val json = """
            {"t":"events","v":${PeerProtocol.VERSION},"events":[
              {"id":"evt-00000001","device":"device-fixed-0001","dataset":"$hash","incident":"incident-a","segment":1,"reason":"SAFARI","occurredAt":1}
            ]}
        """.trimIndent()
        val decoded = decode(json.toByteArray())
        assertTrue(decoded is DecodeResult.Unsupported)
    }

    @Test
    fun invalidIdentifiersAreRejected() {
        val shortId = """
            {"t":"events","v":${PeerProtocol.VERSION},"events":[
              {"id":"x","device":"device-fixed-0001","dataset":"$hash","incident":"incident-a","segment":1,"reason":"BLOCKED","occurredAt":1}
            ]}
        """.trimIndent()
        assertTrue(decode(shortId.toByteArray()) is DecodeResult.Malformed)

        val badDevice = """
            {"t":"events","v":${PeerProtocol.VERSION},"events":[
              {"id":"evt-00000001","device":"bad device","dataset":"$hash","incident":"incident-a","segment":1,"reason":"BLOCKED","occurredAt":1}
            ]}
        """.trimIndent()
        assertTrue(decode(badDevice.toByteArray()) is DecodeResult.Malformed)
    }

    @Test
    fun negativeSegmentAndEmptyBatchAreRejected() {
        val negative = """
            {"t":"events","v":${PeerProtocol.VERSION},"events":[
              {"id":"evt-00000001","device":"device-fixed-0001","dataset":"$hash","incident":"incident-a","segment":-1,"reason":"BLOCKED","occurredAt":1}
            ]}
        """.trimIndent()
        assertTrue(decode(negative.toByteArray()) is DecodeResult.Malformed)
        assertTrue(decode("""{"t":"events","v":${PeerProtocol.VERSION},"events":[]}""".toByteArray()) is DecodeResult.Malformed)
    }

    @Test
    fun batchLargerThanTheLimitIsRejected() {
        val event = """{"id":"evt-00000001","device":"device-fixed-0001","dataset":"$hash","incident":"incident-a","segment":1,"reason":"BLOCKED","occurredAt":1}"""
        val many = (1..(PeerProtocol.MAX_EVENTS_PER_BATCH + 1)).joinToString(",") { event }
        assertTrue(decode("""{"t":"events","v":${PeerProtocol.VERSION},"events":[$many]}""".toByteArray()) is DecodeResult.Malformed)
    }

    @Test
    fun stringsAreEscapedSafely() {
        val tricky = PeerMessage.Hello(
            protocolVersion = PeerProtocol.VERSION,
            deviceId = DeviceId("device-fixed-0001"),
            datasetHash = hash,
            incidentId = "incident\"quoted\\slash",
        )
        val decoded = decode(PeerMessageCodec.encode(tricky))
        assertTrue(decoded is DecodeResult.Ok)
        assertEquals("incident\"quoted\\slash", ((decoded as DecodeResult.Ok).message as PeerMessage.Hello).incidentId)
    }

    // ------------------------------------------------- the v2 observation schema (XPH-78)

    @Test
    fun eventBatchRoundTripsBothAxes() {
        val events = listOf(
            eventWithAxes(level = WaterLevelCategory.KNEE, passability = PassabilityStatus.CAUTION),
            eventWithAxes(
                id = "evt-00000002",
                reason = HazardReason.BLOCKED,
                level = WaterLevelCategory.NO_VISIBLE_WATER,
                passability = PassabilityStatus.IMPASSABLE,
            ),
        )
        val encoded = PeerMessageCodec.encode(PeerMessage.EventBatch(events))
        val json = encoded.decodeToString()
        assertTrue("the level must be on the wire as its pinned code", json.contains("\"level\":\"KNEE\""))
        assertTrue(
            "the passability must be on the wire as its pinned code",
            json.contains("\"passability\":\"CAUTION\""),
        )

        val decoded = decode(encoded)
        assertTrue("expected Ok but was $decoded", decoded is DecodeResult.Ok)
        assertEquals(events, (decoded as DecodeResult.Ok).message.let { (it as PeerMessage.EventBatch).events })
    }

    @Test
    fun aReasonOnlyEventKeepsTheV1Meaning() {
        // The documented default: an event that states no axis is read through the same legacy
        // mapping that reads a v1 stored row (ADR 0001 D2), which is the fail-closed direction -
        // the report is a hard exclusion and no depth is invented for it.
        val decoded = decode(payload().toByteArray())
        assertTrue("expected Ok but was $decoded", decoded is DecodeResult.Ok)
        val event = ((decoded as DecodeResult.Ok).message as PeerMessage.EventBatch).events.single()

        assertEquals(RoadObservation.fromLegacy(HazardReason.WATERLOGGED), event.observation)
        assertEquals(WaterLevelCategory.UNKNOWN, event.observation.waterLevel)
        assertTrue("a reason-only event still excludes", event.observation.excludesTraversal)
    }

    @Test
    fun aHalfStatedObservationIsRefused() {
        // One axis without the other has no documented meaning: the axes are separate observations
        // and neither is ever inferred from the other (ADR 0001 D1). Reading it as the legacy
        // default would be inventing the missing axis, so it is refused instead.
        assertTrue(decode(payload(axes = """, "level":"KNEE"""").toByteArray()) is DecodeResult.Malformed)
        assertTrue(
            decode(payload(axes = """, "passability":"CAUTION"""").toByteArray()) is DecodeResult.Malformed,
        )
    }

    @Test
    fun anUnknownAxisCodeIsRefusedAsUnsupported() {
        assertTrue(
            decode(payload(axes = """, "level":"DEEP","passability":"CAUTION"""").toByteArray())
                is DecodeResult.Unsupported,
        )
        assertTrue(
            decode(payload(axes = """, "level":"KNEE","passability":"MAYBE"""").toByteArray())
                is DecodeResult.Unsupported,
        )
    }

    @Test
    fun anImpossibleObservationIsRefused() {
        // R2: above-waist is defined as unsafe, so it cannot be reported passable.
        assertTrue(
            decode(payload(axes = """, "level":"ABOVE_WAIST","passability":"PASSABLE_REPORTED"""").toByteArray())
                is DecodeResult.Malformed,
        )
        // R1: an event that states nothing is not information, and storing it would inflate the
        // distinct-device support count with an empty report (ADR 0001 D5).
        assertTrue(
            decode(payload(axes = """, "level":"UNKNOWN","passability":"UNKNOWN"""").toByteArray())
                is DecodeResult.Malformed,
        )
    }

    @Test
    fun aNonStringAxisIsRefusedRatherThanTreatedAsAbsent() {
        // Presence is checked apart from type, because reading an unreadable axis as an ABSENT one
        // would silently acquire the legacy default's meaning - a number becoming an exclusion.
        assertTrue(
            decode(payload(axes = """, "level":5,"passability":"CAUTION"""").toByteArray())
                is DecodeResult.Malformed,
        )
        assertTrue(
            decode(payload(axes = """, "level":5,"passability":6""").toByteArray()) is DecodeResult.Malformed,
        )
        assertTrue(
            decode(payload(axes = """, "level":null,"passability":"CAUTION"""").toByteArray())
                is DecodeResult.Malformed,
        )
    }

    @Test
    fun anOlderProtocolBuildIsRefusedRatherThanPartiallyUnderstood() {
        // v1 is the shape that predates the axes. Its meaning was "hard exclusion", which a v2
        // receiver could read correctly - but only by inferring, and the same tolerance would let a
        // v1 receiver misread OUR two-axis report as an exclusion the reporter never stated. Both
        // directions are therefore refused, at the payload, before any handshake comparison.
        val v1Event = """
            {"t":"events","v":1,"events":[
              {"id":"evt-00000001","device":"device-fixed-0001","dataset":"$hash","incident":"incident-a","segment":1,"reason":"WATERLOGGED","occurredAt":1}
            ]}
        """.trimIndent()
        val decoded = decode(v1Event.toByteArray())
        assertTrue("expected Unsupported but was $decoded", decoded is DecodeResult.Unsupported)

        val v1Hello = """{"t":"hello","v":1,"device":"device-fixed-0001","dataset":"$hash","incident":"incident-a"}"""
        assertTrue(decode(v1Hello.toByteArray()) is DecodeResult.Unsupported)
    }

    @Test
    fun theWireCarriesExactlyTheDocumentedEventFieldsAndNothingElse() {
        // An allow-list rather than a deny-list, because the invariant is "a field is a protocol
        // decision". It fails if a position, a fix accuracy (XPH-90), the sender's source typing or
        // a receipt stamp (ADR 0005 section 6.2: the only freshness input no peer may influence)
        // ever becomes wire traffic.
        val encoded = PeerMessageCodec.encode(PeerMessage.EventBatch(listOf(eventWithAxes())))
        val root = JsonParser.parse(encoded.decodeToString()) as JsonValue.ObjectValue
        val event = (root.values["events"] as JsonValue.ArrayValue).values.single() as JsonValue.ObjectValue

        assertEquals(
            "a new event field is a protocol change: update this list deliberately",
            setOf(
                "id", "device", "dataset", "incident", "segment",
                "reason", "level", "passability", "occurredAt",
            ),
            event.values.keys,
        )
        for (forbidden in listOf("receivedAt", "receivedEpochMillis", "accuracy", "source", "lat", "lon")) {
            assertFalse("nothing may travel as '$forbidden'", event.values.containsKey(forbidden))
        }
    }

    @Test
    fun theSenderDatasetFormatVersionIsNotAWireField() {
        // The wire carries the dataset hash, which is the identity the repository compares. The
        // local format version is deliberately not traffic, so two mutations that differ only in it
        // are the same message - and the decoder substitutes its own protocol version.
        val asOne = mutation().copy(context = HazardContext(DatasetIdentity(hash, 1), "incident-a"))
        val asOther = mutation().copy(context = HazardContext(DatasetIdentity(hash, 999), "incident-a"))
        assertTrue(
            PeerMessageCodec.encode(PeerMessage.EventBatch(listOf(asOne)))
                .contentEquals(PeerMessageCodec.encode(PeerMessage.EventBatch(listOf(asOther)))),
        )
    }

    @Test
    fun theSizeAndBatchBoundsAreUnchanged() {
        // Carrying the axes must not relax either bound (acceptance criterion).
        assertEquals(8 * 1024, PeerProtocol.MAX_PAYLOAD_BYTES)
        assertEquals(32, PeerProtocol.MAX_EVENTS_PER_BATCH)
    }

    /** An event stating both axes, built the way the repository builds one. */
    private fun eventWithAxes(
        id: String = "evt-00000001",
        reason: HazardReason = HazardReason.WATERLOGGED,
        level: WaterLevelCategory = WaterLevelCategory.KNEE,
        passability: PassabilityStatus = PassabilityStatus.CAUTION,
    ): HazardMutation {
        val observation = when (val outcome = RoadObservation.create(reason, level, passability)) {
            is ObservationValidation.Valid -> outcome.observation
            is ObservationValidation.Invalid -> error(outcome.problem)
        }
        return mutation(id = id, reason = reason).copy(observation = observation)
    }

    /**
     * A hand-written payload, so a receiver can be tested field by field - including shapes the
     * encoder would never produce.
     */
    private fun payload(
        axes: String = "",
        reason: String = "WATERLOGGED",
        version: Int = PeerProtocol.VERSION,
    ): String = """
        {"t":"events","v":$version,"events":[
          {"id":"evt-00000001","device":"device-fixed-0001","dataset":"$hash","incident":"incident-a","segment":1,"reason":"$reason"$axes,"occurredAt":1}
        ]}
    """.trimIndent()
}
