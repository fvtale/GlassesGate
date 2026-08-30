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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.glassesgate.app.MainActivity
import com.glassesgate.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the user's beacon is doing, as far as the UI is concerned. */
sealed interface BeaconStatus {
    data object Stopped : BeaconStatus

    /** The service is up but deliberately silent -- see [reason]. */
    data class Waiting(val reason: String) : BeaconStatus

    data class Broadcasting(val label: String, val gate: String) : BeaconStatus

    data class Failed(val message: String) : BeaconStatus
}

/**
 * Runs the user's beacon as a foreground service, so it keeps working with the screen off and
 * the app in the background -- which is the only way it is any use at an actual door.
 *
 * The rule this service exists to enforce: **advertise only while a DAT session with the bound
 * glasses is live.** If the glasses are folded, out of range, off, or simply a different pair,
 * the advertisement stops. That is what makes the glasses the credential rather than the phone.
 */
class BeaconService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var vault: CredentialVault
    private lateinit var link: GlassesLink
    private lateinit var advertiser: BeaconAdvertiser

    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        vault = CredentialVault(this)
        link = GlassesLink(scope)
        advertiser = BeaconAdvertiser(this, scope) { message -> fail(message) }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> begin()
        }
        // Deliberately not sticky: if the process dies, silently resurrecting a credential
        // beacon without the user asking is the wrong default for an access token.
        return START_NOT_STICKY
    }

    private fun begin() {
        val active = vault.active
        if (active == null) {
            fail("No enrollment on this phone yet.")
            shutdown()
            return
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.beacon_notification_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        if (!hasAdvertisePermission()) {
            fail(getString(R.string.error_advertise_permission))
            return
        }

        link.connect(active.boundDeviceId)
        watchJob?.cancel()
        watchJob = scope.launch {
            link.state.collect { state -> onLinkState(state, active) }
        }
    }

    private fun onLinkState(state: GlassesState, active: BoundCredential) {
        when (state) {
            is GlassesState.Connected -> {
                if (state.deviceId != active.boundDeviceId) {
                    // A different pair of glasses is connected. The credential was issued
                    // against one specific pair, so this must not count.
                    stopAdvertising(getString(R.string.beacon_wrong_glasses))
                    return
                }
                if (!hasAdvertisePermission()) {
                    fail(getString(R.string.error_advertise_permission))
                    return
                }
                advertiser.start(active.secret, active.credentialId)
                update(BeaconStatus.Broadcasting(active.label, active.gate))
            }
            GlassesState.Connecting -> stopAdvertising(getString(R.string.beacon_connecting))
            GlassesState.Paused -> stopAdvertising(getString(R.string.beacon_paused))
            GlassesState.Idle -> stopAdvertising(getString(R.string.beacon_no_session))
            is GlassesState.Failed -> fail(state.message)
        }
    }

    private fun stopAdvertising(reason: String) {
        advertiser.stop()
        update(BeaconStatus.Waiting(reason))
    }

    private fun fail(message: String) {
        advertiser.stop()
        update(BeaconStatus.Failed(message))
    }

    private fun update(next: BeaconStatus) {
        _status.value = next
        val text = when (next) {
            is BeaconStatus.Broadcasting ->
                getString(R.string.beacon_notification_active, next.gate.ifEmpty { next.label })
            is BeaconStatus.Waiting -> next.reason
            is BeaconStatus.Failed -> next.message
            BeaconStatus.Stopped -> getString(R.string.beacon_notification_stopped)
        }
        if (next != BeaconStatus.Stopped) {
            notificationManager().notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun shutdown() {
        watchJob?.cancel()
        watchJob = null
        advertiser.stop()
        link.disconnect()
        _status.value = BeaconStatus.Stopped
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        advertiser.stop()
        _status.value = BeaconStatus.Stopped
        scope.cancel()
        super.onDestroy()
    }

    private fun hasAdvertisePermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED

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
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.beacon_channel_name),
            // The notification is a legal requirement of a foreground service, not news.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.beacon_channel_description) }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val ACTION_STOP = "com.glassesgate.app.STOP_BEACON"
        private const val CHANNEL_ID = "glassesgate_beacon"
        private const val NOTIFICATION_ID = 1

        private val _status = MutableStateFlow<BeaconStatus>(BeaconStatus.Stopped)

        /**
         * Process-wide, so the UI can observe the beacon without binding to the service. The
         * service is a singleton by construction, so a single flow is honest here.
         */
        val status: StateFlow<BeaconStatus> = _status.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BeaconService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BeaconService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
