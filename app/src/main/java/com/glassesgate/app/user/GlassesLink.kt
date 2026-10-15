package com.glassesgate.app.user

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.glassesgate.core.BeaconPolicy
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.RegistrationState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A pair of glasses this phone knows about, flattened out of the SDK's types. */
data class GlassesDevice(
    val id: String,
    val name: String,
    /** Null until the SDK has reported this pair's link state at all. */
    val isLinked: Boolean?,
    val wear: BeaconPolicy.Wear,
    /** The glasses or this app need updating before they will work together. */
    val needsUpdate: Boolean,
)

/** The one session this process may hold, as the beacon policy sees it. */
data class GlassesSession(
    val phase: BeaconPolicy.Session,
    val deviceId: String?,
    /**
     * Counts every failed attempt. Two failures in a row leave [phase] at FAILED both times, and a
     * StateFlow does not re-emit an equal value -- so without this, a retry that failed again
     * would be invisible and the beacon would never back off and try a third time.
     */
    val failureCount: Int = 0,
)

sealed interface SdkState {
    data object NotStarted : SdkState

    /** The SDK must not be initialized before BLUETOOTH_CONNECT is granted. */
    data object NeedsPermission : SdkState

    data object Ready : SdkState

    data class Failed(val message: String) : SdkState
}

/**
 * Everything that touches the Meta Wearables Device Access Toolkit lives here, and AGENTS.md rule 3
 * is enforced in CI: no other file may import `com.meta.wearable`. The SDK's types are mirrored
 * into plain Kotlin flows before anything else sees them.
 *
 * One instance per process, held by [com.glassesgate.app.GlassesGateApp]. The screen reads it and
 * the beacon service drives it, so both always agree about what the glasses are doing.
 *
 * GlassesGate asks the SDK for a session and nothing else. No camera, microphone, or display
 * capability is ever attached, so the app never requests a device permission and never sees
 * anything the wearer sees.
 */
class GlassesLink(context: Context) {

    private val appContext = context.applicationContext

    private val failures = CoroutineExceptionHandler { _, error ->
        // A collector dying must not take the process with it -- the gate role works with no SDK
        // at all, and a crash loop on the wearer's phone is worse than a message saying why.
        Log.e(TAG, "DAT collector failed", error)
        _sdk.value = SdkState.Failed(error.message ?: error.javaClass.simpleName)
    }

    /**
     * One thread for every session mutation. Session callbacks, the service's connect requests,
     * and stop timeouts all touch [activeSession]; serializing them is simpler and more honest
     * than locking around state that the SDK also mutates behind our back.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + failures,
    )

    private val started = AtomicBoolean(false)

    private val _sdk = MutableStateFlow<SdkState>(SdkState.NotStarted)
    val sdk: StateFlow<SdkState> = _sdk.asStateFlow()

    /**
     * Kept as the SDK's own identifier type, because [SpecificDeviceSelector] needs that value
     * back. Everything else sees the string projection.
     */
    private val identifiers = MutableStateFlow<List<DeviceIdentifier>>(emptyList())

    private val _devices = MutableStateFlow<List<GlassesDevice>>(emptyList())
    val devices: StateFlow<List<GlassesDevice>> = _devices.asStateFlow()

    private val _registered = MutableStateFlow(false)

    /** Whether this app is connected to the Meta AI app. Nothing works until this is true. */
    val registered: StateFlow<Boolean> = _registered.asStateFlow()

    private val _session = MutableStateFlow(GlassesSession(BeaconPolicy.Session.NONE, null))
    val session: StateFlow<GlassesSession> = _session.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** The most recent thing the SDK complained about, for display. Cleared on a good start. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var activeSession: DeviceSession? = null
    private var sessionObservers: CoroutineScope? = null

    /**
     * Initializes the SDK and starts observing it, if the Bluetooth permission is held. Safe to
     * call repeatedly; returns whether the SDK is ready.
     *
     * Call from the main thread: Meta's samples initialize there, and it is cheap.
     */
    fun start(): Boolean {
        if (!hasConnectPermission()) {
            _sdk.value = SdkState.NeedsPermission
            return false
        }
        if (!started.compareAndSet(false, true)) return _sdk.value == SdkState.Ready

        var failure: String? = null
        Wearables.initialize(appContext).onFailure { error, _ -> failure = error.description }
        failure?.let { message ->
            Log.e(TAG, "DAT failed to initialize: $message")
            _sdk.value = SdkState.Failed(message)
            started.set(false) // Allow a later attempt, e.g. after the Meta AI app is installed.
            return false
        }

        _sdk.value = SdkState.Ready
        scope.launch {
            Wearables.registrationState.collect { registration ->
                _registered.value = registration == RegistrationState.REGISTERED
            }
        }
        scope.launch { observeDevices() }
        return true
    }

    fun startRegistration(activity: Activity) {
        if (start()) Wearables.startRegistration(activity)
    }

    fun startUnregistration(activity: Activity) {
        if (start()) Wearables.startUnregistration(activity)
    }

    /** Every pair the SDK reports, whether or not its metadata has arrived. */
    fun knownDeviceIds(): List<String> = identifiers.value.map { it.toString() }

    /** Opens a session on [deviceId] unless one is already open. Never blocks the caller. */
    fun connect(deviceId: String) {
        scope.launch { openSession(deviceId) }
    }

    /** Asks the SDK to end the session, and stops waiting for it after a few seconds. */
    fun disconnect() {
        scope.launch { closeSession() }
    }

    private fun openSession(deviceId: String) {
        if (_sdk.value != SdkState.Ready || activeSession != null) return

        val match = identifiers.value.firstOrNull { it.toString() == deviceId }
        if (match == null) {
            fail("Those glasses are not paired with this phone.")
            return
        }

        setSession(BeaconPolicy.Session.STARTING, deviceId)
        Wearables.createSession(SpecificDeviceSelector(match)).fold(
            onSuccess = { created ->
                activeSession = created
                // Observe before start(), so no initial transition is missed.
                observe(created, deviceId)
                created.start()
            },
            onFailure = { error, _ ->
                Log.e(TAG, "createSession failed: ${error.description}")
                fail(error.description)
            },
        )
    }

    private suspend fun closeSession() {
        val closing = activeSession ?: return
        if (_session.value.phase == BeaconPolicy.Session.STOPPING) return

        _session.value = _session.value.copy(phase = BeaconPolicy.Session.STOPPING)
        closing.stop()

        // STOPPED normally arrives through the state collector. If it never does, a session
        // wedged in STOPPING would block every reconnect forever, so give up on it.
        delay(STOP_TIMEOUT_MILLIS)
        if (activeSession === closing) {
            Log.w(TAG, "session did not report STOPPED; releasing it")
            release()
            setSession(BeaconPolicy.Session.NONE, null)
        }
    }

    private fun observe(session: DeviceSession, deviceId: String) {
        val observers = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        sessionObservers = observers
        observers.launch {
            session.state.collect { state -> onSessionState(session, state, deviceId) }
        }
        observers.launch {
            // Errors are reported, not acted on. Most are informational, and the terminal ones
            // are followed by the session stopping, which the state collector handles. Treating
            // every error as a failure used to kill a beacon whose session was still healthy.
            session.errors.collect { error ->
                Log.w(TAG, "session error: ${error.description}")
                _lastError.value = error.description
            }
        }
    }

    private fun onSessionState(session: DeviceSession, state: DeviceSessionState, deviceId: String) {
        if (session !== activeSession) return
        val phase = when (state) {
            DeviceSessionState.STARTED -> BeaconPolicy.Session.STARTED
            DeviceSessionState.PAUSED -> BeaconPolicy.Session.PAUSED
            DeviceSessionState.STOPPING -> BeaconPolicy.Session.STOPPING
            DeviceSessionState.STOPPED -> {
                // Terminal: a stopped session never restarts. The policy will ask for a new one.
                release()
                BeaconPolicy.Session.NONE
            }
            // IDLE, STARTING, and any state a later SDK adds. Fail closed: an unrecognised state
            // is treated as not live, never as live.
            else -> BeaconPolicy.Session.STARTING
        }
        if (phase == BeaconPolicy.Session.STARTED) _lastError.value = null
        setSession(phase, if (phase == BeaconPolicy.Session.NONE) null else deviceId)
    }

    private fun setSession(phase: BeaconPolicy.Session, deviceId: String?) {
        _session.value = GlassesSession(phase, deviceId, _session.value.failureCount)
    }

    private fun fail(message: String) {
        _lastError.value = message
        _session.value = GlassesSession(
            BeaconPolicy.Session.FAILED,
            deviceId = null,
            failureCount = _session.value.failureCount + 1,
        )
    }

    private fun release() {
        sessionObservers?.cancel()
        sessionObservers = null
        activeSession = null
    }

    /**
     * Follows every paired device's metadata, which is where the name, the real link state, and
     * the wear state live. `Wearables.devices` on its own includes pairs that are merely
     * remembered. Combined rather than collected one by one, so a device appearing or vanishing
     * restarts the whole set cleanly.
     */
    private suspend fun observeDevices() {
        Wearables.devices.collectLatest { ids ->
            val list = ids.toList()
            identifiers.value = list
            if (list.isEmpty()) {
                _devices.value = emptyList()
                return@collectLatest
            }

            // Keep what is already known while the new set's metadata arrives, so the screen
            // does not flash "unknown" every time a pair is added.
            val previous = _devices.value.associateBy { it.id }
            _devices.value = list.map { previous[it.toString()] ?: unreported(it.toString()) }

            val readings = list.map { id ->
                val key = id.toString()
                Wearables.devicesMetadata[id]?.map { describe(key, it) } ?: flowOf(unreported(key))
            }
            combine(readings) { it.toList() }.collect { _devices.value = it }
        }
    }

    private fun describe(id: String, device: Device) = GlassesDevice(
        id = id,
        // The SDK leaves the name empty until the pair has described itself.
        name = device.name.ifBlank { DEFAULT_DEVICE_NAME },
        isLinked = device.linkState == LinkState.CONNECTED,
        wear = when (device.donState) {
            DonState.DONNED -> BeaconPolicy.Wear.WORN
            DonState.DOFFED -> BeaconPolicy.Wear.NOT_WORN
            else -> BeaconPolicy.Wear.UNKNOWN
        },
        needsUpdate = device.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED ||
            device.compatibility == DeviceCompatibility.SDK_UPDATE_REQUIRED,
    )

    private fun unreported(id: String) = GlassesDevice(
        id = id,
        name = DEFAULT_DEVICE_NAME,
        isLinked = null,
        wear = BeaconPolicy.Wear.UNKNOWN,
        needsUpdate = false,
    )

    private fun hasConnectPermission() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "GlassesLink"
        const val DEFAULT_DEVICE_NAME = "Meta glasses"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
