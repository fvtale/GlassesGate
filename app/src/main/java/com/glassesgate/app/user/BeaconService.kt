package com.glassesgate.app.user

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.glassesgate.app.MainActivity
import com.glassesgate.app.R
import com.glassesgate.app.data.bluetoothEnabled
import com.glassesgate.app.glassesGate
import com.glassesgate.core.BeaconPolicy
import com.glassesgate.core.BeaconPolicy.LinkAction
import com.glassesgate.core.BeaconPolicy.Reason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** What the user's beacon is doing, as far as the UI is concerned. */
sealed interface BeaconStatus {
    data object Stopped : BeaconStatus

    /** Running but deliberately silent -- see [reason]. */
    data class Waiting(val reason: String) : BeaconStatus

    data class Broadcasting(val label: String, val gate: String) : BeaconStatus

    data class Failed(val message: String) : BeaconStatus
}

/**
 * Runs the wearer's beacon as a foreground service, so it keeps working with the screen off --
 * the only way it is any use at an actual door.
 *
 * This class decides nothing. It observes the credential, the glasses, the session, and the
 * radio; hands them to [BeaconPolicy]; and does what comes back. The policy is where the rule
 * lives and where it is tested. The service's jobs are plumbing: retry with backoff, report
 * status, and keep the foreground-service contract.
 *
 * Because the loop re-runs on every change, recovery is not a special case. Glasses go out of
 * range and come back: the session stops, the policy asks to connect again. The wearer switches
 * enrollments: the policy sees a session on the wrong pair and closes it, then opens the right
 * one. The previous version had neither behaviour -- it connected once, before the device list
 * had even arrived, and never tried again.
 */
class BeaconService : Service() {

    // Main thread throughout: Bluetooth callbacks arrive there anyway, and one thread means the
    // advertiser and the backoff bookkeeping need no locking.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var advertiser: BeaconAdvertiser

    private var pipeline: Job? = null

    private val retryAllowed = MutableStateFlow(true)
    private var retryJob: Job? = null
    private var backoffMillis = INITIAL_BACKOFF_MILLIS
    private var seenFailures = -1

    private var notificationText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        advertiser = BeaconAdvertiser(this, scope)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        // startForeground() has to come first, on every start, even on a path that is about to
        // give up. Stopping a service started with startForegroundService() before it has called
        // startForeground() crashes the app -- which is what the previous version did when
        // there was no enrollment to broadcast.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (pipeline?.isActive != true) pipeline = scope.launch { run() }
        // Not sticky: if the process dies, silently resurrecting a credential beacon nobody
        // asked for is the wrong default for an access token.
        return START_NOT_STICKY
    }

    private suspend fun run() {
        val link = glassesGate.link
        if (!link.start()) {
            shutdown(BeaconStatus.Failed(getString(R.string.error_glasses_sdk)))
            return
        }
        _running.value = true
        seenFailures = link.session.value.failureCount

        val steps = combine(
            glassesGate.vault.active,
            link.devices,
            link.session,
            bluetoothEnabled(this),
            retryAllowed,
        ) { credential, devices, session, radioOn, retry ->
            val sessionDevice = devices.firstOrNull { it.id == session.deviceId }
            val inputs = BeaconPolicy.Inputs(
                boundDeviceId = credential?.boundDeviceId,
                // Linked, or not yet reported either way. A pair the SDK says is unlinked is left
                // alone rather than asked for a session it cannot give.
                candidateDeviceIds = devices.filter { it.isLinked != false }.map { it.id }.toSet(),
                session = session.phase,
                sessionDeviceId = session.deviceId,
                sessionDeviceLinked = sessionDevice?.isLinked == true,
                wear = sessionDevice?.wear ?: BeaconPolicy.Wear.UNKNOWN,
                retryAllowed = retry,
            )
            Step(credential, session, radioOn, BeaconPolicy.decide(inputs))
        }

        combine(steps.onEach { act(it) }, advertiser.state, link.lastError) { step, onAir, error ->
            render(step, onAir, error)
        }.collect { publish(it) }
    }

    private class Step(
        val credential: BoundCredential?,
        val session: GlassesSession,
        val radioOn: Boolean,
        val decision: BeaconPolicy.Decision,
    )

    private fun act(step: Step) {
        trackFailures(step.session)

        val credential = step.credential
        val link = glassesGate.link
        when (step.decision.link) {
            LinkAction.CONNECT -> credential?.let { link.connect(it.boundDeviceId) }
            LinkAction.DISCONNECT -> link.disconnect()
            LinkAction.NONE -> Unit
        }

        if (step.decision.advertise && step.radioOn && credential != null) {
            advertiser.start(credential.secret, credential.credentialId)
        } else {
            advertiser.stop()
        }

        // The enrollment was forgotten while the beacon ran. Nothing left to do.
        if (step.decision.reason == Reason.NO_ENROLLMENT) shutdown()
    }

    /**
     * Backs off after each failed attempt to open a session: 2s, 4s, 8s, up to a minute. Keyed by
     * the link's failure counter rather than by its phase, because two failures in a row leave
     * the phase unchanged and would otherwise look like one.
     */
    private fun trackFailures(session: GlassesSession) {
        if (session.phase == BeaconPolicy.Session.STARTED) backoffMillis = INITIAL_BACKOFF_MILLIS
        if (session.failureCount == seenFailures) return
        seenFailures = session.failureCount

        retryAllowed.value = false
        retryJob?.cancel()
        val wait = backoffMillis
        backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
        retryJob = scope.launch {
            delay(wait)
            retryAllowed.value = true
        }
    }

    private fun render(step: Step, onAir: BeaconAdvertiser.State, lastError: String?): BeaconStatus {
        if (!step.radioOn) return BeaconStatus.Waiting(getString(R.string.beacon_bluetooth_off))
        val credential = step.credential
        return when (step.decision.reason) {
            Reason.BROADCASTING -> when (onAir) {
                BeaconAdvertiser.State.OnAir ->
                    BeaconStatus.Broadcasting(credential?.label.orEmpty(), credential?.gate.orEmpty())
                is BeaconAdvertiser.State.Failed -> BeaconStatus.Failed(onAir.message)
                else -> BeaconStatus.Waiting(getString(R.string.beacon_starting))
            }
            Reason.NO_ENROLLMENT -> BeaconStatus.Failed(getString(R.string.beacon_no_enrollment))
            Reason.GLASSES_NOT_NEARBY -> BeaconStatus.Waiting(getString(R.string.beacon_no_session))
            Reason.CONNECTING -> BeaconStatus.Waiting(getString(R.string.beacon_connecting))
            Reason.NOT_LINKED -> BeaconStatus.Waiting(getString(R.string.beacon_not_linked))
            Reason.NOT_WORN -> BeaconStatus.Waiting(getString(R.string.beacon_not_worn))
            Reason.PAUSED -> BeaconStatus.Waiting(getString(R.string.beacon_paused))
            Reason.WRONG_GLASSES -> BeaconStatus.Waiting(getString(R.string.beacon_wrong_glasses))
            Reason.RETRY_PENDING -> BeaconStatus.Waiting(
                getString(
                    R.string.beacon_retrying,
                    lastError ?: getString(R.string.beacon_retrying_generic),
                ),
            )
        }
    }

    private fun publish(status: BeaconStatus) {
        _status.value = status
        val text = when (status) {
            is BeaconStatus.Broadcasting ->
                getString(R.string.beacon_notification_active, status.gate.ifEmpty { status.label })
            is BeaconStatus.Waiting -> status.reason
            is BeaconStatus.Failed -> status.message
            BeaconStatus.Stopped -> return
        }
        if (text == notificationText) return
        notificationText = text
        notificationManager().notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.beacon_notification_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        true
    } catch (e: Exception) {
        // A connectedDevice service needs a Bluetooth permission; start() checks first, so this
        // is the backstop for a grant revoked in between.
        Log.e(TAG, "startForeground failed", e)
        _status.value = BeaconStatus.Failed(getString(R.string.error_advertise_permission))
        false
    }

    private fun shutdown(final: BeaconStatus = BeaconStatus.Stopped) {
        pipeline?.cancel()
        pipeline = null
        retryJob?.cancel()
        advertiser.stop()
        glassesGate.link.disconnect()
        _running.value = false
        _status.value = final
        notificationText = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        advertiser.stop()
        glassesGate.link.disconnect()
        _running.value = false
        if (_status.value !is BeaconStatus.Failed) _status.value = BeaconStatus.Stopped
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BeaconService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_beacon)
            .setContentTitle(getString(R.string.beacon_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop_beacon), stop)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.beacon_channel_name),
            // The notification is a requirement of a foreground service, not news.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.beacon_channel_description) }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "BeaconService"
        private const val ACTION_STOP = "com.glassesgate.app.STOP_BEACON"
        private const val CHANNEL_ID = "glassesgate_beacon"
        private const val NOTIFICATION_ID = 1
        private const val INITIAL_BACKOFF_MILLIS = 2_000L
        private const val MAX_BACKOFF_MILLIS = 60_000L

        private val _status = MutableStateFlow<BeaconStatus>(BeaconStatus.Stopped)

        /**
         * Process-wide, so the UI can observe the beacon without binding to the service. Holds
         * the last failure after the service stops, so the wearer can see why.
         */
        val status: StateFlow<BeaconStatus> = _status.asStateFlow()

        private val _running = MutableStateFlow(false)

        /** Whether the service is up. Distinct from [status], which can outlive it. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /**
         * Starts the beacon, or reports why it cannot. Checks the permissions a connected-device
         * foreground service needs up front, because failing inside the service is a crash.
         */
        fun start(context: Context) {
            val missing = listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            ).any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing) {
                _status.value = BeaconStatus.Failed(context.getString(R.string.error_advertise_permission))
                return
            }
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BeaconService::class.java),
                )
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: only startable from the foreground.
                Log.e(TAG, "could not start beacon service", e)
                _status.value = BeaconStatus.Failed(context.getString(R.string.error_start_beacon))
            }
        }

        fun stop(context: Context) {
            if (!_running.value) {
                _status.value = BeaconStatus.Stopped
                return
            }
            context.startService(Intent(context, BeaconService::class.java).setAction(ACTION_STOP))
        }
    }
}
