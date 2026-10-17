package com.resqroute.data.p2p

import android.content.Context
import android.os.Build
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.resqroute.domain.sync.PeerSessionId
import com.resqroute.domain.sync.RadioOperation
import com.resqroute.domain.sync.TransportState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Real foreground Nearby Connections transport (P2P_CLUSTER).
 *
 * This class owns radios, endpoints and bytes. It performs **no** hazard logic
 * and never auto-accepts a connection: acceptance is driven by the coordinator
 * after the user compares the authentication code.
 *
 * Incoming payloads go through a bounded channel. If the channel is full the
 * session is failed and disconnected rather than silently dropping a hazard
 * command, so reconnect reconciliation can recover (acceptance criterion 6).
 *
 * Every failure is surfaced as a [TransportEvent]; nothing here throws into the
 * caller and missing Play services / permissions / radios never crash the app.
 */
class NearbyNetworkManager(
    context: Context,
    /** Installation identity, used to build a stable, unique advertising alias. */
    private val installationIdProvider: () -> String? = { null },
    private val deviceAlias: String = "ResQRoute-" + (Build.MODEL ?: "device"),
) : P2PNetworkManager {

    /**
     * Advertising name. Carries a short installation id so two peers can break
     * the connection-request tie deterministically instead of both dialing at
     * once (crossing requests are a real source of failed sessions).
     */
    private val alias: String
        get() = installationIdProvider()?.take(8)?.takeIf { it.isNotBlank() }?.let { "RQR-$it" }
            ?: deviceAlias

    private companion object {
        const val SERVICE_ID = "com.resqroute.hazard.sync.v1"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
        const val INCOMING_CAPACITY = 64
    }

    private val appContext = context.applicationContext

    private val client: ConnectionsClient? = try {
        Nearby.getConnectionsClient(appContext)
    } catch (_: Throwable) {
        null
    }

    private val _state = MutableStateFlow<TransportState>(TransportState.Stopped)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TransportEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    private val payloadChannel = Channel<IncomingPayload>(capacity = INCOMING_CAPACITY)
    override val incoming: Flow<IncomingPayload> = payloadChannel.receiveAsFlow()

    private val endpointNames = ConcurrentHashMap<String, String>()
    private val connected = ConcurrentHashMap.newKeySet<String>()
    private val attemptedConnections = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var advertising: RadioOperation = RadioOperation.STOPPED

    @Volatile
    private var discovery: RadioOperation = RadioOperation.STOPPED

    private fun emit(event: TransportEvent) {
        _events.tryEmit(event)
    }

    private fun updateState() {
        _state.value = TransportState.Running(advertising, discovery)
    }

    // ------------------------------------------------------------- lifecycle

    override fun start() {
        val connections = client ?: run {
            _state.value = TransportState.Unavailable("Google Play services is not available on this device")
            emit(TransportEvent.Failure("start", "Google Play services is not available on this device"))
            return
        }
        val missing = NearbyPermissions.missing(appContext)
        if (missing.isNotEmpty()) {
            _state.value = TransportState.Unavailable("missing permissions: ${missing.size}")
            emit(
                TransportEvent.Failure(
                    "permission",
                    "missing runtime permissions: ${missing.joinToString { it.substringAfterLast('.') }}",
                ),
            )
            return
        }
        updateState()
        startAdvertising(connections)
        startDiscovery(connections)
    }

    private fun startAdvertising(connections: ConnectionsClient) {
        advertising = RadioOperation.STARTING
        updateState()
        try {
            val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
            connections.startAdvertising(alias, SERVICE_ID, lifecycleCallback, options)
                .addOnSuccessListener {
                    advertising = RadioOperation.ACTIVE
                    updateState()
                }
                .addOnFailureListener {
                    advertising = RadioOperation.FAILED
                    updateState()
                    emit(TransportEvent.Failure("advertise", it.message ?: it.toString()))
                }
        } catch (t: Throwable) {
            advertising = RadioOperation.FAILED
            updateState()
            emit(TransportEvent.Failure("advertise", t.message ?: t.toString()))
        }
    }

    private fun startDiscovery(connections: ConnectionsClient) {
        discovery = RadioOperation.STARTING
        updateState()
        try {
            val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
            connections.startDiscovery(SERVICE_ID, discoveryCallback, options)
                .addOnSuccessListener {
                    discovery = RadioOperation.ACTIVE
                    updateState()
                }
                .addOnFailureListener {
                    discovery = RadioOperation.FAILED
                    updateState()
                    emit(TransportEvent.Failure("discover", it.message ?: it.toString()))
                }
        } catch (t: Throwable) {
            discovery = RadioOperation.FAILED
            updateState()
            emit(TransportEvent.Failure("discover", t.message ?: t.toString()))
        }
    }

    override fun stop() {
        try {
            client?.stopAdvertising()
            client?.stopDiscovery()
            client?.stopAllEndpoints()
        } catch (t: Throwable) {
            emit(TransportEvent.Failure("stop", t.message ?: t.toString()))
        }
        advertising = RadioOperation.STOPPED
        discovery = RadioOperation.STOPPED
        connected.clear()
        endpointNames.clear()
        attemptedConnections.clear()
        _state.value = TransportState.Stopped
    }

    override fun connect(sessionId: PeerSessionId) {
        val connections = client ?: return
        // One outstanding attempt per endpoint; duplicates are dropped.
        if (!attemptedConnections.add(sessionId.value)) return
        try {
            connections.requestConnection(alias, sessionId.value, lifecycleCallback)
                .addOnFailureListener {
                    attemptedConnections -= sessionId.value
                    emit(TransportEvent.Failure("connect", it.message ?: it.toString(), sessionId))
                }
        } catch (t: Throwable) {
            attemptedConnections -= sessionId.value
            emit(TransportEvent.Failure("connect", t.message ?: t.toString(), sessionId))
        }
    }

    override fun accept(sessionId: PeerSessionId) {
        try {
            client?.acceptConnection(sessionId.value, payloadCallback)
                ?.addOnFailureListener { emit(TransportEvent.Failure("accept", it.message ?: it.toString(), sessionId)) }
        } catch (t: Throwable) {
            emit(TransportEvent.Failure("accept", t.message ?: t.toString(), sessionId))
        }
    }

    override fun reject(sessionId: PeerSessionId) {
        try {
            client?.rejectConnection(sessionId.value)
                ?.addOnFailureListener { emit(TransportEvent.Failure("reject", it.message ?: it.toString(), sessionId)) }
        } catch (t: Throwable) {
            emit(TransportEvent.Failure("reject", t.message ?: t.toString(), sessionId))
        }
    }

    override fun disconnect(sessionId: PeerSessionId) {
        try {
            client?.disconnectFromEndpoint(sessionId.value)
        } catch (t: Throwable) {
            emit(TransportEvent.Failure("disconnect", t.message ?: t.toString(), sessionId))
        }
        connected -= sessionId.value
    }

    override fun send(sessionId: PeerSessionId, bytes: ByteArray): Boolean {
        val connections = client ?: return false
        return try {
            connections.sendPayload(sessionId.value, Payload.fromBytes(bytes))
                .addOnFailureListener { emit(TransportEvent.Failure("send", it.message ?: it.toString(), sessionId)) }
            true
        } catch (t: Throwable) {
            emit(TransportEvent.Failure("send", t.message ?: t.toString(), sessionId))
            false
        }
    }

    // -------------------------------------------------------------- callbacks

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpointNames[endpointId] = info.endpointName
            emit(TransportEvent.EndpointFound(PeerSessionId(endpointId), info.endpointName))
        }

        override fun onEndpointLost(endpointId: String) {
            emit(TransportEvent.EndpointLost(PeerSessionId(endpointId)))
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            emit(
                TransportEvent.ConnectionRequested(
                    sessionId = PeerSessionId(endpointId),
                    endpointName = info.endpointName,
                    authCode = info.authenticationDigits.toString().padStart(4, '0'),
                    incoming = info.isIncomingConnection,
                ),
            )
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val status = resolution.status
            attemptedConnections -= endpointId
            if (status.isSuccess) {
                connected += endpointId
                emit(TransportEvent.Connected(PeerSessionId(endpointId), endpointNames[endpointId] ?: endpointId))
            } else {
                connected -= endpointId
                emit(
                    TransportEvent.Failure(
                        "connect",
                        "connection failed: " + (status.statusMessage ?: "code ${status.statusCode}"),
                        PeerSessionId(endpointId),
                    ),
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected -= endpointId
            attemptedConnections -= endpointId
            emit(TransportEvent.Disconnected(PeerSessionId(endpointId)))
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) {
                emit(TransportEvent.Failure("receive", "unsupported payload type ${payload.type}", PeerSessionId(endpointId)))
                return
            }
            val bytes = try {
                payload.asBytes()
            } catch (t: Throwable) {
                emit(TransportEvent.Failure("receive", t.message ?: t.toString(), PeerSessionId(endpointId)))
                return
            } ?: return

            val result = payloadChannel.trySend(IncomingPayload(PeerSessionId(endpointId), bytes))
            if (result.isFailure) {
                // Explicit failure, never a silent drop: the session is closed so
                // reconnect reconciliation retransmits the event log.
                emit(
                    TransportEvent.Failure(
                        "receive-queue",
                        "incoming queue overflow; closing session",
                        PeerSessionId(endpointId),
                    ),
                )
                disconnect(PeerSessionId(endpointId))
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                emit(TransportEvent.Failure("send", "payload transfer failed", PeerSessionId(endpointId)))
            }
        }
    }
}
