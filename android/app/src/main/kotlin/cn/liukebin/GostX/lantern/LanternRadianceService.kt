package cn.liukebin.gostx.lantern

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.system.Os
import androidx.core.app.NotificationCompat
import cn.liukebin.gostx.MainActivity
import cn.liukebin.gostx.R
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import lantern.io.mobile.Mobile
import lantern.io.utils.FlutterEvent
import lantern.io.utils.FlutterEventEmitter
import lantern.io.utils.Opts

/**
 * Current Lantern/Radiance backend for GostX.
 *
 * Radiance runs in local SOCKS mode on 127.0.0.1:18080. A small bridge exposes
 * that as an ordinary LAN HTTP proxy on 0.0.0.0:8080 for TVs and other devices.
 */
class LanternRadianceService : Service() {
    companion object {
        private const val ACTION_START = "cn.liukebin.gostx.lantern.RADIANCE_START"
        private const val ACTION_STOP = "cn.liukebin.gostx.lantern.RADIANCE_STOP"
        private const val CHANNEL_ID = "gostx_radiance"
        private const val NOTIFICATION_ID = 38081
        private const val SOCKS_HOST = "127.0.0.1"
        private const val SOCKS_PORT = 18080
        private const val LAN_PORT = 8080
        private const val PREFS = "radiance_proxy"
        private const val PREF_DEVICE_ID = "device_id"

        fun start(context: Context) {
            // Port 8080 belongs to one backend at a time. Stop legacy first.
            context.stopService(Intent(context, LanternProxyService::class.java))
            val intent = Intent(context, LanternRadianceService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LanternRadianceService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val stopping = AtomicBoolean(false)
    @Volatile private var lanProxy: LanHttpToSocksProxy? = null
    @Volatile private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> executor.execute { stopBackend() }
            ACTION_START, null -> {
                startForeground(NOTIFICATION_ID, notification("Radiance hazırlanıyor"))
                if (RadianceProxyState.state.value.status != RadianceStatus.STARTING &&
                    RadianceProxyState.state.value.status != RadianceStatus.RUNNING) {
                    executor.execute { startBackend() }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping.set(true)
        runCatching { lanProxy?.stop() }
        lanProxy = null
        executor.execute {
            runCatching {
                if (Mobile.isRadianceConnected()) Mobile.stopVPN()
            }
        }
        super.onDestroy()
    }

    private fun startBackend() {
        stopping.set(false)
        startedAt = System.currentTimeMillis()
        val deviceId = stableDeviceId()
        RadianceProxyState.update {
            it.copy(
                status = RadianceStatus.STARTING,
                stage = "Radiance başlatılıyor",
                deviceId = deviceId,
                elapsedMs = null,
                error = null,
                lastEvent = null
            )
        }

        try {
            configureRadianceEnvironment()
            val dataDir = File(filesDir, "lantern-radiance").apply { mkdirs() }
            val logDir = File(dataDir, "logs").apply { mkdirs() }
            val platform = RadiancePlatform(applicationContext)
            val opts = Opts().apply {
                this.dataDir = dataDir.absolutePath
                this.logDir = logDir.absolutePath
                this.logLevel = "debug"
                this.deviceid = deviceId
                this.locale = Locale.getDefault().language.ifBlank { "en" }
                this.telemetryConsent = false
                this.env = "prod"
                this.platform = platform
            }
            val emitter = object : FlutterEventEmitter {
                override fun sendEvent(p0: FlutterEvent?) {
                    if (p0 == null) return
                    RadianceProxyState.update { state ->
                        state.copy(lastEvent = "${p0.type}: ${p0.message}")
                    }
                }
            }

            setStage("Radiance IPC hazırlanıyor")
            Mobile.startIPCServer(platform, opts)
            if (stopping.get()) return

            setStage("Lantern sunucu/config bilgisi alınıyor")
            Mobile.setupRadiance(opts, emitter)
            if (stopping.get()) return

            setStage("Akıllı sunucu seçiliyor; bu birkaç dakika sürebilir")
            Mobile.startVPN()
            if (stopping.get()) return

            setStage("Radiance SOCKS bekleniyor")
            waitForSocks(180_000)

            setStage("LAN HTTP proxy açılıyor")
            lanProxy?.stop()
            lanProxy = LanHttpToSocksProxy(
                listenHost = "0.0.0.0",
                listenPort = LAN_PORT,
                socksHost = SOCKS_HOST,
                socksPort = SOCKS_PORT
            ).also { it.start() }

            val elapsed = System.currentTimeMillis() - startedAt
            RadianceProxyState.update {
                it.copy(
                    status = RadianceStatus.RUNNING,
                    stage = "Bağlandı - güncel Lantern/Radiance",
                    elapsedMs = elapsed,
                    error = null
                )
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification("Radiance bağlı - LAN proxy :8080"))
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            runCatching { lanProxy?.stop() }
            lanProxy = null
            runCatching { if (Mobile.isRadianceConnected()) Mobile.stopVPN() }
            RadianceProxyState.update {
                it.copy(
                    status = RadianceStatus.ERROR,
                    stage = "Radiance hatası",
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    error = msg
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopBackend() {
        if (!stopping.compareAndSet(false, true)) return
        RadianceProxyState.update { it.copy(stage = "Kapatılıyor") }
        runCatching { lanProxy?.stop() }
        lanProxy = null
        runCatching {
            if (Mobile.isRadianceConnected()) Mobile.stopVPN()
        }
        RadianceProxyState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun waitForSocks(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Throwable? = null
        while (!stopping.get() && System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(SOCKS_HOST, SOCKS_PORT), 1000)
                }
                return
            } catch (t: Throwable) {
                last = t
                Thread.sleep(1000)
            }
        }
        throw IllegalStateException("Radiance SOCKS $SOCKS_HOST:$SOCKS_PORT açılmadı", last)
    }

    private fun configureRadianceEnvironment() {
        // These are documented Radiance development/runtime switches. SOCKS mode
        // avoids creating a second Android system VPN and lets GostX keep its LAN
        // proxy architecture intact.
        Os.setenv("RADIANCE_USE_SOCKS_PROXY", "true", true)
        Os.setenv("RADIANCE_SOCKS_ADDRESS", "$SOCKS_HOST:$SOCKS_PORT", true)
        Os.setenv("RADIANCE_COUNTRY", "cn", true)
        Os.setenv("RADIANCE_ENV", "prod", true)
    }

    private fun stableDeviceId(): String {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(PREF_DEVICE_ID, null)
        if (!current.isNullOrBlank()) return current
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_DEVICE_ID, created).apply()
        return created
    }

    private fun setStage(stage: String) {
        RadianceProxyState.update {
            it.copy(
                status = RadianceStatus.STARTING,
                stage = stage,
                elapsedMs = System.currentTimeMillis() - startedAt
            )
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification(stage))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Lantern Radiance Proxy",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun notification(text: String): android.app.Notification {
        val launch = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_vpn)
            .setContentTitle("GostX - Lantern Radiance")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }
}
