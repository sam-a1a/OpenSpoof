package com.sam.openspoof.mock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.sam.openspoof.MainActivity
import com.sam.openspoof.R
import com.sam.openspoof.map.GeoPoint
import com.sam.openspoof.map.formatLatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a spoofed position published for as long as the user wants it.
 *
 * Test providers only live as long as the process that registered them, so without a foreground
 * service the spoof would evaporate the moment the app is swapped away and the process reclaimed.
 * That is the entire reason this is a service rather than something the Activity owns.
 */
class SpoofService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine by lazy { MockLocationEngine(this) }
    private var pushJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
                val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
                if (lat.isNaN() || lon.isNaN()) {
                    shutdown()
                    return START_NOT_STICKY
                }
                begin(GeoPoint(lat, lon))
            }

            else -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun begin(point: GeoPoint) {
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(point),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        if (!engine.start()) {
            // The only realistic cause is the app no longer being the selected mock location
            // app. Reporting it rather than silently idling lets the UI re-prompt.
            _state.value = SpoofState.Failed
            stopSelf()
            return
        }

        _state.value = SpoofState.Active(point)
        pushJob?.cancel()
        pushJob = scope.launch {
            // Republished on a cycle rather than once: a single fix goes stale, and clients
            // that subscribe after the spoof starts would otherwise never receive anything.
            while (isActive) {
                engine.push(point.lat, point.lon)
                delay(PUSH_INTERVAL_MS)
            }
        }
    }

    private fun shutdown() {
        pushJob?.cancel()
        pushJob = null
        engine.stop()
        _state.value = SpoofState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        pushJob?.cancel()
        engine.stop()
        if (_state.value !is SpoofState.Failed) _state.value = SpoofState.Idle
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            // Low: the notification is a required, permanent status indicator, not an alert.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(point: GeoPoint): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SpoofService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(formatLatLon(point.lat, point.lon))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.action_stop), stop).build(),
            )
            .build()
    }

    companion object {
        private const val ACTION_START = "com.sam.openspoof.action.START"
        private const val ACTION_STOP = "com.sam.openspoof.action.STOP"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val CHANNEL_ID = "spoof"
        private const val NOTIFICATION_ID = 1
        private const val PUSH_INTERVAL_MS = 1000L

        private val _state = MutableStateFlow<SpoofState>(SpoofState.Idle)

        /** Observed by the UI so the map reflects the spoof even after the Activity is recreated. */
        val state: StateFlow<SpoofState> = _state.asStateFlow()

        fun start(context: Context, point: GeoPoint) {
            context.startForegroundService(
                Intent(context, SpoofService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_LAT, point.lat)
                    .putExtra(EXTRA_LON, point.lon),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SpoofService::class.java).setAction(ACTION_STOP),
            )
        }

        /** Clears a [SpoofState.Failed] latch once the UI has shown it. */
        fun acknowledgeFailure() {
            if (_state.value is SpoofState.Failed) _state.value = SpoofState.Idle
        }
    }
}

sealed interface SpoofState {
    data object Idle : SpoofState
    data class Active(val point: GeoPoint) : SpoofState

    /** Providers could not be installed, almost always a revoked mock location selection. */
    data object Failed : SpoofState
}
