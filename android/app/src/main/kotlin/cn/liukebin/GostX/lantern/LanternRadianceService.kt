package cn.liukebin.gostx.lantern

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.os.Process as AndroidProcess
import androidx.core.app.NotificationCompat
import cn.liukebin.gostx.MainActivity
import cn.liukebin.gostx.R
import cn.liukebin.gostx.service.GostVpnService
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Current Lantern/Radiance backend for GostX.
 *
 * Radiance runs as a separate native novpn process. This is intentional: the
 * app already embeds libgost.aar, and two gomobile AARs would ship two copies
 * of go.Seq/libgojni and collide. A native process gives each Go runtime its
 * own address space and lets identity rotation restart Radiance cleanly without
 * touching the regular Gost VPN engine.
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
        private const val PREF_USAGE_RX_BYTES = "usage_rx_bytes"
        private const val PREF_ROTATION_COUNT = "rotation_count"
        private const val USAGE_ROTATE_BYTES = 500L * 1024L * 1024L
        private const val USAGE_SAMPLE_SECONDS = 5L

        fun start(context: Context) {
            // Only one backend should own the user's traffic/proxy path at a time.
            context.stopService(Intent(context, LanternProxyService::class.java))
            GostVpnService.stop(context)
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

    private data class SessionExit(val code: Int, val wasReady: Boolean)

    private val backendExecutor = Executors.newSingleThreadExecutor()
    private val controlExecutor = Executors.newSingleThreadExecutor()
    private val usageExecutor = Executors.newSingleThreadScheduledExecutor()
    private val stopping = AtomicBoolean(false)
    private val backendLoopRunning = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)
    private val rotationInProgress = AtomicBoolean(false)

    @Volatile private var radianceProcess: Process? = null
    @Volatile private var startedAt = 0L
    @Volatile private var lastUidRxBytes = TrafficStats.UNSUPPORTED

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        usageExecutor.scheduleWithFixedDelay(
            { runCatching { sampleUsageAndRotateIfNeeded() } },
            USAGE_SAMPLE_SECONDS,
            USAGE_SAMPLE_SECONDS,
            TimeUnit.SECONDS
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> controlExecutor.execute { stopBackend() }
            ACTION_START, null -> {
                stopping.set(false)
                startForeground(NOTIFICATION_ID, notification("Radiance hazırlanıyor"))
                if (backendLoopRunning.compareAndSet(false, true)) {
                    backendExecutor.execute { backendLoop() }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping.set(true)
        restartRequested.set(false)
        stopProcess(waitForExit = false)
        usageExecutor.shutdownNow()
        controlExecutor.shutdownNow()
        backendExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun backendLoop() {
        try {
            while (!stopping.get()) {
                val exit = try {
                    runRadianceSession()
                } catch (t: Throwable) {
                    if (restartRequested.get() && !stopping.get()) {
                        SessionExit(-1, false)
                    } else {
                        throw t
                    }
                }

                if (stopping.get()) break
                if (restartRequested.getAndSet(false)) {
                    rotationInProgress.set(false)
                    setStage("Yeni UUID ile yeniden bağlanıyor")
                    Thread.sleep(500)
                    continue
                }

                throw IllegalStateException(
                    if (exit.wasReady) {
                        "Radiance beklenmedik şekilde kapandı (${exit.code})"
                    } else {
                        "Radiance başlatılamadı (${exit.code})"
                    }
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
        } finally {
            backendLoopRunning.set(false)
            rotationInProgress.set(false)
        }
    }

    private fun runRadianceSession(): SessionExit {
        startedAt = System.currentTimeMillis()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val deviceId = stableDeviceId()
        RadianceProxyState.update {
            it.copy(
                status = RadianceStatus.STARTING,
                stage = "Radiance başlatılıyor",
                deviceId = deviceId,
                usageBytes = prefs.getLong(PREF_USAGE_RX_BYTES, 0L),
                rotationCount = prefs.getInt(PREF_ROTATION_COUNT, 0),
                elapsedMs = null,
                error = null
            )
        }

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
        lastUidRxBytes = TrafficStats.getUidRxBytes(AndroidProcess.myUid())

        var ready = false
        process.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (stopping.get()) break
                when {
                    line.startsWith("RADIANCE_READY ") -> {
                        ready = true
                        val actual = line.removePrefix("RADIANCE_READY ").trim()
                        val elapsed = System.currentTimeMillis() - startedAt
                        RadianceProxyState.update {
                            it.copy(
                                status = RadianceStatus.RUNNING,
                                stage = "Bağlandı - güncel Lantern/Radiance",
                                address = "0.0.0.0:$LAN_PORT",
                                socksAddress = actual,
                                elapsedMs = elapsed,
                                error = null
                            )
                        }
                        notifyText("Radiance bağlı - LAN proxy :$LAN_PORT")
                    }
                    line.startsWith("RADIANCE_SELECTED_JSON ") -> {
                        parseSelectedServer(line.removePrefix("RADIANCE_SELECTED_JSON ").trim())
                    }
                    line.startsWith("RADIANCE_SELECTED ") -> {
                        val tag = line.removePrefix("RADIANCE_SELECTED ").trim()
                        RadianceProxyState.update {
                            it.copy(selectedTag = tag, lastEvent = "Seçilen Lantern sunucusu: $tag")
                        }
                    }
                    line.contains("error", ignoreCase = true) -> {
                        RadianceProxyState.update { it.copy(lastEvent = line.takeLast(500)) }
                    }
                }
            }
        }

        val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
        if (radianceProcess === process) radianceProcess = null
        return SessionExit(exitCode, ready)
    }

    private fun parseSelectedServer(raw: String) {
        runCatching {
            val obj = JSONObject(raw)
            val tag = obj.optString("tag")
            val protocol = obj.optString("type")
            val location = obj.optJSONObject("location")
            val city = location?.optString("city").orEmpty()
            val country = location?.optString("country").orEmpty()
            val place = listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")
            val readable = listOf(place, protocol, tag).filter { it.isNotBlank() }.joinToString(" • ")
            RadianceProxyState.update {
                it.copy(
                    selectedTag = tag,
                    selectedProtocol = protocol,
                    selectedLocation = place,
                    lastEvent = if (readable.isBlank()) "Smart Location güncellendi" else readable
                )
            }
        }
    }

    private fun sampleUsageAndRotateIfNeeded() {
        val current = TrafficStats.getUidRxBytes(AndroidProcess.myUid())
        if (current == TrafficStats.UNSUPPORTED) return

        val previous = lastUidRxBytes
        lastUidRxBytes = current
        if (radianceProcess?.isAlive != true) return
        if (previous == TrafficStats.UNSUPPORTED || current < previous) return

        val delta = current - previous
        if (delta <= 0L) return

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val total = prefs.getLong(PREF_USAGE_RX_BYTES, 0L) + delta
        if (total < USAGE_ROTATE_BYTES) {
            prefs.edit().putLong(PREF_USAGE_RX_BYTES, total).apply()
            RadianceProxyState.update { it.copy(usageBytes = total) }
            return
        }

        if (!rotationInProgress.compareAndSet(false, true)) return

        val remainder = total % USAGE_ROTATE_BYTES
        val newDeviceId = UUID.randomUUID().toString()
        val newCount = prefs.getInt(PREF_ROTATION_COUNT, 0) + 1
        val saved = prefs.edit()
            .putLong(PREF_USAGE_RX_BYTES, remainder)
            .putString(PREF_DEVICE_ID, newDeviceId)
            .putInt(PREF_ROTATION_COUNT, newCount)
            .commit()
        if (!saved) {
            rotationInProgress.set(false)
            return
        }

        RadianceProxyState.update {
            it.copy(
                status = RadianceStatus.STARTING,
                stage = "500 MB tamamlandı - UUID yenileniyor",
                deviceId = newDeviceId,
                usageBytes = remainder,
                rotationCount = newCount,
                lastEvent = "Lantern client UUID yenilendi; sunucu kimlik bilgileri değiştirilmedi"
            )
        }
        notifyText("500 MB - Lantern UUID yenileniyor")
        restartRequested.set(true)
        stopProcess(waitForExit = false)
    }

    private fun stopBackend() {
        if (!stopping.compareAndSet(false, true)) return
        restartRequested.set(false)
        rotationInProgress.set(false)
        RadianceProxyState.update { it.copy(stage = "Kapatılıyor") }
        stopProcess(waitForExit = true)
        RadianceProxyState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopProcess(waitForExit: Boolean) {
        val process = radianceProcess ?: return
        radianceProcess = null
        runCatching { process.destroy() }
        if (waitForExit) {
            runCatching {
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }

    private fun stableDeviceId(): String {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(PREF_DEVICE_ID, null)
        if (!current.isNullOrBlank()) return current
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_DEVICE_ID, created).commit()
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
