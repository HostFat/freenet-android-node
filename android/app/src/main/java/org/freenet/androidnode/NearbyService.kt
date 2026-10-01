package org.freenet.androidnode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal object NearbyHub {
    private val peersMutable = kotlinx.coroutines.flow.MutableStateFlow(0)
    val peers: kotlinx.coroutines.flow.StateFlow<Int> = peersMutable

    private val statusMutable = kotlinx.coroutines.flow.MutableStateFlow("")
    val status: kotlinx.coroutines.flow.StateFlow<String> = statusMutable

    private val askMutable = kotlinx.coroutines.flow.MutableStateFlow("")
    val askResult: kotlinx.coroutines.flow.StateFlow<String> = askMutable

    @Volatile private var engine: NearbyEngine? = null

    fun attach(next: NearbyEngine) {
        engine = next
    }

    fun detach(current: NearbyEngine) {
        if (engine === current) {
            engine = null
            peersMutable.value = 0
        }
    }

    fun publishPeers(count: Int) {
        peersMutable.value = count
    }

    fun publishStatus(text: String) {
        statusMutable.value = text
    }

    fun publishAsk(text: String) {
        askMutable.value = text
    }

    fun ask(raw: String) {
        val current = engine
        if (current == null) {
            askMutable.value = "Turn Bluetooth or Wi-Fi on first."
            return
        }
        current.ask(raw)
    }

    fun askAll(raws: List<String>) {
        val current = engine
        if (current == null || raws.isEmpty()) return
        current.askAll(raws)
    }
}

class NearbyService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: NearbyEngine? = null
    private var policyJob: Job? = null
    private var sessionJob: Job? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        serviceRunning = true
        NodePolicyRepository.initialize(this)
        val created = NearbyEngine(
            context = this,
            onPeers = NearbyHub::publishPeers,
            onStatus = NearbyHub::publishStatus,
            onAsk = NearbyHub::publishAsk,
        )
        engine = created
        NearbyHub.attach(created)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (!showForeground()) {
            requestStop()
            return START_NOT_STICKY
        }
        val policy = NodePolicyRepository.state.value
        val now = System.currentTimeMillis()
        if (!policy.nearbyBluetooth && !policy.nearbyWifi) {
            requestStop()
            return START_NOT_STICKY
        }
        if (!nearbyNodeIsRunning(NodeRepository.state.value.state)) {
            requestStop()
            return START_NOT_STICKY
        }
        if (
            policy.nearbySessionStartedEpochMs > 0L &&
            nearbySessionExpired(policy.nearbySessionStartedEpochMs, policy.nearbySessionMinutes, now)
        ) {
            NodePolicyRepository.clearNearbyRadios(this)
            requestStop()
            return START_NOT_STICKY
        }
        if (policyJob == null) {
            policyJob = serviceScope.launch {
                NodePolicyRepository.state.collect { next ->
                    val current = engine ?: return@collect
                    if (!next.nearbyBluetooth && !next.nearbyWifi) {
                        current.apply(bluetooth = false, wifi = false)
                        requestStop()
                    } else {
                        current.apply(next.nearbyBluetooth, next.nearbyWifi)
                    }
                }
            }
            sessionJob = serviceScope.launch {
                while (isActive) {
                    delay(SESSION_CHECK_MS)
                    val next = NodePolicyRepository.state.value
                    if (!next.nearbyBluetooth && !next.nearbyWifi) {
                        requestStop()
                        break
                    }
                    if (!nearbyNodeIsRunning(NodeRepository.state.value.state)) {
                        requestStop()
                        break
                    }
                    if (
                        nearbySessionExpired(
                            next.nearbySessionStartedEpochMs,
                            next.nearbySessionMinutes,
                            System.currentTimeMillis(),
                        )
                    ) {
                        NodePolicyRepository.clearNearbyRadios(this@NearbyService)
                        requestStop()
                        break
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        policyJob?.cancel()
        sessionJob?.cancel()
        val current = engine
        engine = null
        if (current != null) {
            current.stop()
            NearbyHub.detach(current)
        }
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        serviceScope.cancel()
        serviceRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.nearby_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.nearby_notification_text)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_node_notification)
            .setContentTitle(getString(R.string.nearby_notification_title))
            .setContentText(getString(R.string.nearby_notification_text))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()
        return try {
            val type = if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            foregroundStarted = true
            true
        } catch (error: Exception) {
            android.util.Log.e("FreenetNearby", "Nearby foreground start failed", error)
            NearbyHub.publishStatus("Android did not allow nearby sharing to stay running.")
            false
        }
    }

    private fun requestStop() {
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "freenet_nearby"
        private const val NOTIFICATION_ID = 7511
        private const val SESSION_CHECK_MS = 15_000L
        private const val ACTION_STOP = "org.freenet.androidnode.NEARBY_STOP"

        @Volatile
        private var serviceRunning = false

        fun start(context: Context) {
            val intent = Intent(context, NearbyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun restore(context: Context) {
            val app = context.applicationContext
            NodePolicyRepository.initialize(app)
            val policy = NodePolicyRepository.state.value
            if (!policy.nearbyBluetooth && !policy.nearbyWifi) return
            if (!nearbyNodeIsRunning(NodeRepository.state.value.state)) return
            val now = System.currentTimeMillis()
            val expired = policy.nearbySessionStartedEpochMs <= 0L ||
                nearbySessionExpired(policy.nearbySessionStartedEpochMs, policy.nearbySessionMinutes, now)
            if (expired) {
                NodePolicyRepository.clearNearbyRadios(app)
                return
            }
            start(app)
        }

        fun syncWithNode(context: Context) {
            val app = context.applicationContext
            NodePolicyRepository.initialize(app)
            val radiosOn = NodePolicyRepository.state.value.let {
                it.nearbyBluetooth || it.nearbyWifi
            }
            val nodeUp = nearbyNodeIsRunning(NodeRepository.state.value.state)
            if (radiosOn && nodeUp) {
                if (!serviceRunning) restore(app)
            } else if (serviceRunning && !nodeUp) {
                app.startService(
                    Intent(app, NearbyService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
