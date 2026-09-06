package cn.liukebin.gostx.secondary

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cn.liukebin.gostx.MainActivity
import cn.liukebin.gostx.data.GlobalVpnState
import cn.liukebin.gostx.data.VpnStatus
import cn.liukebin.gostx.service.GostVpnService
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

class SecondaryProxyService : Service() {
    companion object {
        const val ACTION_START = "cn.liukebin.gostx.secondary.START"
        const val ACTION_STOP = "cn.liukebin.gostx.secondary.STOP"
        const val LAN_PORT = 8080

        private const val PREFS = "gostx_prefs"
        private const val PREF_DESIRED = "secondary_proxy_running"
        private const val CHANNEL_ID = "gostx_secondary_proxy"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SecondaryProxyService::class.java).apply {
                action = ACTION_START
            })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SecondaryProxyService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthJob: Job? = null
    private var lanternClient: lantern.LanternClient? = null
    private var stoppingByUser = false
    private var startInProgress = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val desired = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_DESIRED, false)

        when (intent?.action) {
            ACTION_STOP -> {
                stoppingByUser = true
                scope.launch { stopProxy(clearDesired = true) }
            }
            ACTION_START -> {
                promoteToForeground("Starting LAN proxy...")
                scope.launch { startProxy() }
            }
            null -> {
                if (desired) {
                    promoteToForeground("Restoring LAN proxy...")
                    scope.launch { startProxy() }
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    @Synchronized
    private fun markStarting(): Boolean {
        if (startInProgress) return false
        startInProgress = true
        return true
    }

    private suspend fun startProxy() {
        if (!markStarting()) return
        try {
            SecondaryProxyState.starting("Stopping primary VPN...")
            stopPrimaryVpnIfNeeded()

            SecondaryProxyState.starting("Starting Lantern Free...")
            val configDir = File(filesDir, "secondary_proxy_config").apply { mkdirs() }

            val client = lantern.LanternClient()
            client.setup("GostX", configDir.absolutePath)

            val result = client.start(":$LAN_PORT", true)
            val returnedAddr = result.addr
            lanternClient = client

            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_DESIRED, true)
                .apply()

            val lanIp = findLanIpv4()
            val shown = if (lanIp.isNotBlank()) "$lanIp:$LAN_PORT" else returnedAddr
            SecondaryProxyState.running(shown)
            promoteToForeground("TV proxy: $shown")
            startHealthCheck(shown)
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            SecondaryProxyState.error(msg)
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_DESIRED, false)
                .apply()
            stopForegroundCompat()
            stopSelf()
        } finally {
            synchronized(this) { startInProgress = false }
        }
    }

    private suspend fun stopPrimaryVpnIfNeeded() {
        val status = GlobalVpnState.state.value.status
        if (status == VpnStatus.CONNECTED ||
            status == VpnStatus.CONNECTING ||
            status == VpnStatus.STOPPING
        ) {
            GostVpnService.stop(this)
            for (i in 0 until 100) {
                val now = GlobalVpnState.state.value.status
                if (now != VpnStatus.CONNECTED &&
                    now != VpnStatus.CONNECTING &&
                    now != VpnStatus.STOPPING
                ) {
                    break
                }
                delay(100L)
            }
        }
    }

    private fun startHealthCheck(shownAddress: String) {
        healthJob?.cancel()
        healthJob = scope.launch {
            delay(12_000L)
            var consecutiveFailures = 0
            while (isActive) {
                if (probeThroughProxy()) {
                    consecutiveFailures = 0
                    SecondaryProxyState.running(shownAddress, "Connection OK")
                    delay(60_000L)
                    continue
                }

                consecutiveFailures++
                if (consecutiveFailures < 2) {
                    SecondaryProxyState.running(shownAddress, "Checking connection...")
                    delay(20_000L)
                    continue
                }

                SecondaryProxyState.reconnecting(shownAddress)
                runCatching { lanternClient?.stop() }
                delay(2_000L)

                val restarted = runCatching {
                    val configDir = File(filesDir, "secondary_proxy_config").apply { mkdirs() }
                    val client = lantern.LanternClient()
                    client.setup("GostX", configDir.absolutePath)
                    client.start(":$LAN_PORT", true)
                    lanternClient = client
                }.isSuccess

                if (restarted) {
                    consecutiveFailures = 0
                    SecondaryProxyState.running(shownAddress, "Reconnected")
                    promoteToForeground("TV proxy: $shownAddress")
                    delay(30_000L)
                } else {
                    SecondaryProxyState.running(shownAddress, "Waiting to retry...")
                    delay(60_000L)
                }
            }
        }
    }

    private fun probeThroughProxy(): Boolean {
        return try {
            val proxy = Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", LAN_PORT)
            )
            val http = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("https://www.google.com/generate_204")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            http.newCall(request).execute().use { response ->
                response.isSuccessful || response.code in 300..399
            }
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun stopProxy(clearDesired: Boolean) {
        healthJob?.cancel()
        healthJob = null
        runCatching { lanternClient?.stop() }
        lanternClient = null

        if (clearDesired) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_DESIRED, false)
                .apply()
        }

        SecondaryProxyState.stopped()
        stopForegroundCompat()
        stopSelf()
    }

    private fun findLanIpv4(): String {
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
                ?.hostAddress
                .orEmpty()
        }.getOrDefault("")
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "LAN Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background LAN proxy status"
            }
        )
    }

    private fun promoteToForeground(text: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            21,
            Intent(this, SecondaryProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lantern Free")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        healthJob?.cancel()
        runCatching { lanternClient?.stop() }
        lanternClient = null
        if (stoppingByUser) {
            SecondaryProxyState.stopped()
        }
        scope.cancel()
        super.onDestroy()
    }
}