package com.glassesgate.app.user

import android.app.Activity
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A pair of glasses this phone knows about, flattened out of the SDK's types. */
data class GlassesDevice(
    val id: String,
    val name: String,
    val isConnected: Boolean,
)

/** What the rest of the app knows about the glasses session. */
sealed interface GlassesState {
    /** No session. Nothing is being proved, so the beacon must not advertise. */
    data object Idle : GlassesState

    data object Connecting : GlassesState

    /** A live DAT session with [deviceId]. The only state the beacon may run in. */
    data class Connected(val deviceId: String) : GlassesState

    /**
     * The SDK suspended the session -- glasses folded, taken off, or another app took over.
     * Treated exactly like [Idle] for beacon purposes: the wearer is not demonstrably present.
     */
    data object Paused : GlassesState

    data class Failed(val message: String) : GlassesState
}

/**
 * Everything that touches the Meta Wearables Device Access Toolkit lives here.
 *
 * DAT types are mirrored into plain Kotlin flows before they reach the rest of the app. The SDK
 * is a developer preview whose shapes move between releases, so this file should be the only one
 * that needs touching when they do.
 *
 * GlassesGate asks the SDK for a session and nothing else -- no camera, microphone, or display
 * capability is ever attached, so the app never requests a device permission and never sees
 * anything the wearer sees. The existence of a session is the entire signal.
 */
class GlassesLink(private val scope: CoroutineScope) {

    /**
     * Kept as the SDK's own identifier type, because [SpecificDeviceSelector] needs that value
     * back. Everything the app sees is the string projection, so nothing downstream has to know
     * what a `DeviceIdentifier` actually is.
     */
    private val identifiers = MutableStateFlow<List<DeviceIdentifier>>(emptyList())

    private val _devices = MutableStateFlow<List<GlassesDevice>>(emptyList())
    val devices: StateFlow<List<GlassesDevice>> = _devices.asStateFlow()

    /**
     * Every pair the SDK reports, whether or not its metadata has arrived yet.
     *
     * Enrollment binds against this rather than against [devices], so that a slow or absent
     * metadata flow cannot make the app look like it has no glasses at all. Binding to a pair
     * that turns out to be unreachable is harmless: the beacon refuses to advertise without a
     * live session regardless, so the check that matters still happens at the door.
     */
    fun knownDeviceIds(): List<String> = identifiers.value.map { it.toString() }

    private val _registered = MutableStateFlow(false)

    /** Whether this app is connected to the Meta AI app. Nothing works until this is true. */
    val registered: StateFlow<Boolean> = _registered.asStateFlow()

    private val _state = MutableStateFlow<GlassesState>(GlassesState.Idle)
    val state: StateFlow<GlassesState> = _state.asStateFlow()

    private var session: DeviceSession? = null
    private val sessionJobs = mutableListOf<Job>()
    private val metadataJobs = mutableMapOf<DeviceIdentifier, Job>()

    init {
        scope.launch {
            Wearables.registrationState.collect { registration ->
                _registered.value = registration == RegistrationState.REGISTERED
            }
        }
        scope.launch {
            Wearables.devices.collect { ids ->
                identifiers.value = ids.toList()
                syncMetadata(ids)
            }
        }
    }

    fun startRegistration(activity: Activity) = Wearables.startRegistration(activity)

    fun startUnregistration(activity: Activity) = Wearables.startUnregistration(activity)


    /**
     * Opens a session with [deviceId]. Resolving the selector against the live identifier list,
     * rather than reconstructing one from the stored string, keeps this correct whatever the
     * SDK's identifier type turns out to be.
     */
    fun connect(deviceId: String) {
        val current = _state.value
        if (current is GlassesState.Connected || current is GlassesState.Connecting) return

        val match = identifiers.value.firstOrNull { it.toString() == deviceId }
        if (match == null) {
            _state.value = GlassesState.Failed("Those glasses are not connected to this phone.")
            return
        }

        _state.value = GlassesState.Connecting
        Wearables.createSession(SpecificDeviceSelector(match)).fold(
            onSuccess = { created ->
                session = created
                // Observe before start(), so no initial transition is missed.
                observe(created, deviceId)
                created.start()
            },
            onFailure = { error, _ ->
                Log.e(TAG, "createSession failed: ${error.description}")
                _state.value = GlassesState.Failed(error.description)
            },
        )
    }

    fun disconnect() {
        session?.stop()
        clearSession()
        _state.value = GlassesState.Idle
    }

    private fun observe(session: DeviceSession, requestedDeviceId: String) {
        sessionJobs += scope.launch {
            session.state.collect { sessionState ->
                _state.value = when (sessionState) {
                    DeviceSessionState.STARTED -> GlassesState.Connected(requestedDeviceId)
                    DeviceSessionState.STARTING -> GlassesState.Connecting
                    DeviceSessionState.PAUSED -> GlassesState.Paused
                    DeviceSessionState.STOPPED -> {
                        clearSession()
                        GlassesState.Idle
                    }
                    // STOPPING, IDLE, and anything a later SDK adds: hold the last known state
                    // rather than inventing a transition the UI would flicker through.
                    else -> _state.value
                }
            }
        }
        sessionJobs += scope.launch {
            session.errors.collect { error ->
                Log.e(TAG, "session error: ${error.description}")
                _state.value = GlassesState.Failed(error.description)
            }
        }
    }

    private fun clearSession() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        session = null
    }

    /**
     * One metadata collector per device, started and stopped as devices appear and disappear.
     * Metadata is where the human-readable name and the actual connection state live --
     * `Wearables.devices` alone includes pairs that are merely remembered.
     */
    private fun syncMetadata(ids: Set<DeviceIdentifier>) {
        (metadataJobs.keys - ids).forEach { gone ->
            metadataJobs.remove(gone)?.cancel()
            _devices.update { list -> list.filterNot { it.id == gone.toString() } }
        }

        // Placeholder rows so the list is never empty while metadata is still in flight; the
        // collector below replaces each one as soon as real metadata arrives.
        _devices.update { list ->
            val known = list.associateBy { it.id }
            ids.map { id ->
                known[id.toString()]
                    ?: GlassesDevice(id.toString(), DEFAULT_DEVICE_NAME, isConnected = false)
            }
        }

        (ids - metadataJobs.keys).forEach { id ->
            metadataJobs[id] = scope.launch {
                Wearables.devicesMetadata[id]?.collect { device ->
                    val entry = GlassesDevice(
                        id = id.toString(),
                        name = device.name.ifEmpty { DEFAULT_DEVICE_NAME },
                        isConnected = device.linkState == LinkState.CONNECTED,
                    )
                    _devices.update { list ->
                        list.filterNot { it.id == entry.id } + entry
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "GlassesLink"
        const val DEFAULT_DEVICE_NAME = "Meta glasses"
    }
}
