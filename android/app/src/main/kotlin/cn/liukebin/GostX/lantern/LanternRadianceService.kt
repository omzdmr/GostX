package cn.liukebin.gostx.lantern

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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

/**
 * Current Lantern/Radiance backend for GostX.
 *
 * Radiance runs as a separate native novpn process. This is intentional: the
 * app already embeds libgost.aar, and two gomobile AARs would ship two copies
 * of go.Seq/libgojni and collide. A native process gives each Go runtime its
 * own address space and is also much easier to restart cleanly after identity
 * rotation or a stuck Smart Location attempt.
 */
class LanternRadianceService : Service() {
    companion object {
        private const val ACTION_START = "cn.liukebin.gostx.lantern.RADIANCE_START"
        private const val ACTION_STOP = "cn.liukebin.gostx.lantern.RADIANCE_STOP"
        private const val CHANNEL_ID = "gostx_radiance"
        private const val NOTIFICATION_ID = 38081
        private const val LAN_PORT = 8080
        private const val PREFS = "radiance_proxy"
        private const val PREF_DEVICE_ID = "device_id"

        fun start(context: Context) {
            context.stopService(Intent(context, LanternProxyService::class.java))
            val intent = Intent(context, LanternRadianceService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LanternRadianceService::class.java).setAction(ACTION_STOP))
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val stopping = AtomicBoolean(false)
    @Volatile private var radianceProcess: Process? = null
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
                if (radianceProcess?.isAlive != true) executor.execute { startBackend() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping.set(true)
        stopProcess()
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
            val binary = File(applicationInfo.nativeLibraryDir, "libradianceproc.so")
            check(binary.exists()) { "Radiance core is missing for this CPU" }
            check(binary.canExecute()) { "Radiance core is not executable" }

            val dataDir = File(filesDir, "lantern-radiance").apply { mkdirs() }
            val logDir = File(dataDir, "logs").apply { mkdirs() }
            val builder = ProcessBuilder(
                binary.absolutePath,
                "--data-dir", dataDir.absolutePath,
                "--log-dir", logDir.absolutePath,
                "--listen", "0.0.0.0:$LAN_PORT",
                "--device-id", deviceId,
                "--locale", Locale.getDefault().language.ifBlank { "en" },
                "--country", "cn",
                "--env", "prod"
            ).redirectErrorStream(true)
            builder.environment()["HOME"] = filesDir.absolutePath
            builder.environment()["TMPDIR"] = cacheDir.absolutePath

            setStage("Lantern sunucu/config bilgisi alınıyor")
            val process = builder.start()
            radianceProcess = process

            var ready = false
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (stopping.get()) break
                    when {
                        line.startsWith("RADIANCE_READY ") -> {
                            ready = true
                            val elapsed = System.currentTimeMillis() - startedAt
                            RadianceProxyState.update {
                                it.copy(
                                    status = RadianceStatus.RUNNING,
                                    stage = "Bağlandı - güncel Lantern/Radiance",
                                    address = "0.0.0.0:$LAN_PORT",
                                    socksAddress = line.removePrefix("RADIANCE_READY ").trim(),
                                    elapsedMs = elapsed,
                                    error = null,
                                    lastEvent = line
                                )
                            }
                            notifyText("Radiance bağlı - LAN proxy :$LAN_PORT")
                        }
                        line.startsWith("RADIANCE_SELECTED ") -> {
                            val tag = line.removePrefix("RADIANCE_SELECTED ").trim()
                            RadianceProxyState.update { it.copy(lastEvent = "Seçilen Lantern sunucusu: $tag") }
                        }
                        else -> RadianceProxyState.update { it.copy(lastEvent = line.takeLast(500)) }
                    }
                }
            }

            val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
            radianceProcess = null
            if (!stopping.get()) {
                throw IllegalStateException(
                    if (ready) "Radiance beklenmedik şekilde kapandı ($exitCode)" else "Radiance başlatılamadı ($exitCode)"
                )
            }
        } catch (t: Throwable) {
            if (!stopping.get()) {
                RadianceProxyState.update {
                    it.copy(
                        status = RadianceStatus.ERROR,
                        stage = "Radiance hatası",
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        error = t.message ?: t.javaClass.simpleName
                    )
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopBackend() {
        if (!stopping.compareAndSet(false, true)) return
        RadianceProxyState.update { it.copy(stage = "Kapatılıyor") }
        stopProcess()
        RadianceProxyState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopProcess() {
        val process = radianceProcess
        radianceProcess = null
        if (process != null) {
            runCatching { process.destroy() }
            runCatching {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
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
        notifyText(stage)
    }

    private fun notifyText(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lantern Radiance Proxy", NotificationManager.IMPORTANCE_LOW)
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
