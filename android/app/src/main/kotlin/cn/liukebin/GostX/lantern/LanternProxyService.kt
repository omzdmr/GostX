package cn.liukebin.gostx.lantern

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import cn.liukebin.gostx.MainActivity
import cn.liukebin.gostx.notification.CHANNEL_ID
import cn.liukebin.gostx.notification.NotificationHelper
import cn.liukebin.gostx.service.GostVpnService
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class LanternProxyService : Service() {

    companion object {
        const val ACTION_START = "cn.liukebin.gostx.lantern.START"
        const val ACTION_STOP = "cn.liukebin.gostx.lantern.STOP"
        const val ACTION_REFRESH_IDENTITY = "cn.liukebin.gostx.lantern.REFRESH_IDENTITY"

        private const val PREFS = "gostx_prefs"
        private const val PREF_RUNNING = "lantern_proxy_running"
        private const val PREF_AUTO_RECOVERY = "lantern_auto_recovery"
        private const val PREF_AUTO_UUID = "lantern_auto_uuid"
        private const val PREF_DEVICE_ID = "lantern_device_id"
        private const val PREF_UUID_DAY = "lantern_uuid_day"
        private const val PREF_UUID_COUNT = "lantern_uuid_count"

        private const val NOTIFICATION_ID = 2
        private const val LAN_PORT = 8080
        private const val TAG = "LanternProxyService"

        const val AUTO_UUID_DAILY_LIMIT = 10

        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val HEALTH_INTERVAL_MS = 30_000L
        private const val RECOVERY_COOLDOWN_MS = 120_000L

        private const val PASSIVE_BAD_SAMPLES = 3
        private const val HEALTH_BAD_SAMPLES = 2
        private const val LATENCY_SUSPECT_MS = 4_500L

        private const val BASELINE_MIN_MBPS = 8.0
        private const val SLOW_ABSOLUTE_MBPS = 5.0
        private const val SLOW_RELATIVE_RATIO = 0.35
        private const val MINI_PROBE_BYTES = 262_144

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, LanternProxyService::class.java).apply {
                    action = ACTION_START
                }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LanternProxyService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }

        fun refreshIdentity(context: Context) {
            context.startForegroundService(
                Intent(context, LanternProxyService::class.java).apply {
                    action = ACTION_REFRESH_IDENTITY
                }
            )
        }

        fun setAutoRecovery(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_AUTO_RECOVERY, enabled)
                .apply()

            val state = LanternProxyState.state.value
            LanternProxyState.setRecoverySettings(
                autoRecoveryEnabled = enabled,
                autoUuidEnabled = state.autoUuidEnabled,
                autoUuidCount = state.autoUuidCount,
                autoUuidLimit = AUTO_UUID_DAILY_LIMIT,
                autoUuidPaused = state.autoUuidPaused,
                deviceId = state.deviceId
            )
        }

        fun setAutoUuidRecovery(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_AUTO_UUID, enabled)
                .apply()

            val state = LanternProxyState.state.value
            LanternProxyState.setRecoverySettings(
                autoRecoveryEnabled = state.autoRecoveryEnabled,
                autoUuidEnabled = enabled,
                autoUuidCount = state.autoUuidCount,
                autoUuidLimit = AUTO_UUID_DAILY_LIMIT,
                autoUuidPaused = enabled && state.autoUuidCount >= AUTO_UUID_DAILY_LIMIT,
                deviceId = state.deviceId
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startInProgress = AtomicBoolean(false)
    private val recoveryInProgress = AtomicBoolean(false)
    private val confirmInProgress = AtomicBoolean(false)

    @Volatile
    private var desiredRunning = false

    @Volatile
    private var lanternProcess: Process? = null

    @Volatile
    private var recoveryCooldownUntil = 0L

    private var monitorJob: Job? = null
    private lateinit var connectivityManager: ConnectivityManager

    private val activeThroughputSamples = ArrayDeque<Double>()
    private var passiveBadSamples = 0
    private var healthBadSamples = 0

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        syncRecoverySettings()

        if (prefs().getBoolean(PREF_RUNNING, false)) {
            LanternProxyState.setStarting()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                desiredRunning = true
                prefs().edit().putBoolean(PREF_RUNNING, true).apply()
                promoteToForeground("Connecting...")
                scope.launch {
                    if (!startLantern() && desiredRunning) {
                        delay(3_000)
                        if (desiredRunning && !recoveryInProgress.get()) {
                            startLantern()
                        }
                    }
                }
            }

            ACTION_STOP -> {
                desiredRunning = false
                prefs().edit().putBoolean(PREF_RUNNING, false).apply()
                scope.launch {
                    monitorJob?.cancel()
                    monitorJob = null
                    stopLanternProcess(updateState = true)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ACTION_REFRESH_IDENTITY -> {
                desiredRunning = true
                prefs().edit().putBoolean(PREF_RUNNING, true).apply()
                promoteToForeground("Refreshing Lantern identity...")
                scope.launch { manualIdentityRefresh() }
            }

            null -> {
                if (prefs().getBoolean(PREF_RUNNING, false)) {
                    desiredRunning = true
                    promoteToForeground("Reconnecting...")
                    scope.launch { startLantern() }
                } else {
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private suspend fun startLantern(): Boolean {
        if (lanternProcess?.isAlive == true) return true
        if (!startInProgress.compareAndSet(false, true)) {
            return lanternProcess?.isAlive == true
        }

        try {
            LanternProxyState.setStarting()
            promoteToForeground("Connecting...")

            GostVpnService.stop(this)
            delay(750)

            if (!hasUsableNetwork()) {
                LanternProxyState.setError("Waiting for an internet-capable network")
                return false
            }

            val binary = File(applicationInfo.nativeLibraryDir, "liblanternproc.so")
            if (!binary.exists()) {
                hardFail(
                    "Lantern core is missing for this CPU " +
                        "(${Build.SUPPORTED_ABIS.joinToString()})"
                )
                return false
            }
            if (!binary.canExecute()) {
                hardFail("Lantern core is not executable: ${binary.absolutePath}")
                return false
            }

            val configDir = File(filesDir, "lantern-free").apply { mkdirs() }
            val deviceId = currentDeviceId()
            syncRecoverySettings()

            val ready = CompletableDeferred<Boolean>()
            val builder = ProcessBuilder(
                binary.absolutePath,
                "--config-dir", configDir.absolutePath,
                "--listen", "0.0.0.0:$LAN_PORT",
                "--proxy-all=true",
                "--device-id", deviceId
            ).redirectErrorStream(true)

            builder.environment()["HOME"] = filesDir.absolutePath
            builder.environment()["TMPDIR"] = cacheDir.absolutePath

            val process = builder.start()
            lanternProcess = process

            scope.launch {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Log.i(TAG, line)
                            if (line.startsWith("LANTERN_READY ")) {
                                val actual = line.removePrefix("LANTERN_READY ").trim()
                                val lanAddress = lanProxyAddress(actual)
                                LanternProxyState.setRunning(lanAddress)
                                promoteToForeground("LAN proxy: $lanAddress")
                                if (!ready.isCompleted) ready.complete(true)
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Lantern output reader stopped", it)
                }
            }

            scope.launch {
                val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
                if (!ready.isCompleted) ready.complete(false)

                if (lanternProcess === process) {
                    lanternProcess = null
                    if (desiredRunning && !recoveryInProgress.get()) {
                        LanternProxyState.setError(
                            "Lantern core exited ($exitCode). Retrying..."
                        )
                        promoteToForeground("Lantern exited. Retrying...")
                        delay(3_000)
                        if (desiredRunning && !recoveryInProgress.get()) {
                            startLantern()
                        }
                    } else if (!desiredRunning) {
                        LanternProxyState.setStopped()
                    }
                }
            }

            val started = withTimeoutOrNull(25_000) { ready.await() } ?: false
            if (started) {
                ensureMonitorLoop()
            } else if (lanternProcess === process) {
                LanternProxyState.setError("Lantern proxy start timed out")
                stopLanternProcess(updateState = false)
            }
            return started
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to start Lantern", t)
            LanternProxyState.setError(t.message ?: t.javaClass.simpleName)
            return false
        } finally {
            startInProgress.set(false)
        }
    }

    private fun ensureMonitorLoop() {
        if (monitorJob?.isActive == true) return

        monitorJob = scope.launch {
            var lastRxBytes = TrafficStats.getUidRxBytes(AndroidProcess.myUid())
            var lastSampleAt = SystemClock.elapsedRealtime()
            var lastHealthAt = 0L

            while (isActive && desiredRunning) {
                delay(SAMPLE_INTERVAL_MS)
                resetDailyCounterIfNeeded()

                if (lanternProcess?.isAlive != true || recoveryInProgress.get()) {
                    lastRxBytes = TrafficStats.getUidRxBytes(AndroidProcess.myUid())
                    lastSampleAt = SystemClock.elapsedRealtime()
                    continue
                }

                val now = SystemClock.elapsedRealtime()
                val rxBytes = TrafficStats.getUidRxBytes(AndroidProcess.myUid())
                if (
                    rxBytes != TrafficStats.UNSUPPORTED.toLong() &&
                    lastRxBytes != TrafficStats.UNSUPPORTED.toLong() &&
                    rxBytes >= lastRxBytes
                ) {
                    val elapsedMs = (now - lastSampleAt).coerceAtLeast(1L)
                    val deltaBytes = rxBytes - lastRxBytes
                    val passiveMbps =
                        (deltaBytes.toDouble() * 8.0 * 1000.0) /
                            (elapsedMs.toDouble() * 1_000_000.0)
                    updatePassiveThroughput(passiveMbps)
                }

                lastRxBytes = rxBytes
                lastSampleAt = now

                if (now - lastHealthAt >= HEALTH_INTERVAL_MS) {
                    lastHealthAt = now
                    val latency = latencyProbe()
                    LanternProxyState.setMetrics(latencyMs = latency)

                    if (latency == null || latency >= LATENCY_SUSPECT_MS) {
                        healthBadSamples++
                        LanternProxyState.setQuality(LanternQuality.SUSPECT)
                        if (healthBadSamples >= HEALTH_BAD_SAMPLES) {
                            triggerRecovery(
                                if (latency == null) {
                                    "Repeated proxy health-check failure"
                                } else {
                                    "Repeated high proxy latency (${latency} ms)"
                                }
                            )
                        }
                    } else {
                        healthBadSamples = 0
                        if (!confirmInProgress.get()) {
                            LanternProxyState.setQuality(LanternQuality.GOOD)
                        }
                    }
                }
            }
        }
    }

    private fun updatePassiveThroughput(mbps: Double) {
        val baseline = baselineMbps()
        LanternProxyState.setMetrics(
            passiveMbps = mbps,
            baselineMbps = if (baseline > 0.0) baseline else null
        )

        if (mbps <= 0.10) {
            passiveBadSamples = 0
            return
        }

        val suspicious =
            baseline >= BASELINE_MIN_MBPS &&
                mbps < SLOW_ABSOLUTE_MBPS &&
                mbps < baseline * SLOW_RELATIVE_RATIO

        if (suspicious) {
            passiveBadSamples++
            LanternProxyState.setQuality(LanternQuality.SUSPECT)
            if (passiveBadSamples >= PASSIVE_BAD_SAMPLES) {
                passiveBadSamples = 0
                confirmPassiveSlowdown(baseline)
            }
            return
        }

        passiveBadSamples = 0
        if (mbps >= SLOW_ABSOLUTE_MBPS) {
            synchronized(activeThroughputSamples) {
                activeThroughputSamples.addLast(mbps)
                while (activeThroughputSamples.size > 12) {
                    activeThroughputSamples.removeFirst()
                }
            }
            LanternProxyState.setMetrics(baselineMbps = baselineMbps())
        }
    }

    private fun confirmPassiveSlowdown(baseline: Double) {
        if (!confirmInProgress.compareAndSet(false, true)) return

        scope.launch {
            try {
                if (!desiredRunning || recoveryInProgress.get()) return@launch

                val first = miniSpeedProbe()
                LanternProxyState.setMetrics(probeMbps = first)
                delay(6_000)
                val second = miniSpeedProbe()
                LanternProxyState.setMetrics(probeMbps = second)

                if (probeIsSlow(first, baseline) && probeIsSlow(second, baseline)) {
                    triggerRecovery(
                        "Sustained throughput collapse " +
                            "(baseline ${formatMbps(baseline)} Mbps)"
                    )
                } else {
                    LanternProxyState.setQuality(LanternQuality.GOOD)
                }
            } finally {
                confirmInProgress.set(false)
            }
        }
    }

    private fun probeIsSlow(speed: Double?, baseline: Double): Boolean {
        if (speed == null) return true
        if (speed >= SLOW_ABSOLUTE_MBPS) return false
        if (baseline < BASELINE_MIN_MBPS) return speed < 1.0
        return speed < baseline * SLOW_RELATIVE_RATIO
    }

    private fun triggerRecovery(reason: String) {
        if (!prefs().getBoolean(PREF_AUTO_RECOVERY, true)) return
        if (SystemClock.elapsedRealtime() < recoveryCooldownUntil) return
        if (!recoveryInProgress.compareAndSet(false, true)) return

        scope.launch {
            try {
                performRecovery(reason)
            } finally {
                passiveBadSamples = 0
                healthBadSamples = 0
                recoveryCooldownUntil =
                    SystemClock.elapsedRealtime() + RECOVERY_COOLDOWN_MS
                recoveryInProgress.set(false)
            }
        }
    }

    private suspend fun performRecovery(reason: String) {
        if (!desiredRunning) return
        if (!hasUsableNetwork()) {
            LanternProxyState.setNotice("Recovery postponed: phone network is unavailable.")
            return
        }

        LanternProxyState.setRecovering(reason)
        promoteToForeground("Recovering: $reason")

        if (restartLantern(clearProxyConfig = false, fullConfigRefresh = false)) {
            delay(3_000)
            if (recoveryHealthIsGood()) {
                recoverySucceeded("Process restart")
                return
            }
        }

        LanternProxyState.setRecovering("$reason • refreshing server config")
        if (restartLantern(clearProxyConfig = true, fullConfigRefresh = false)) {
            delay(3_000)
            if (recoveryHealthIsGood()) {
                recoverySucceeded("Server/config refresh")
                return
            }
        }

        val rotated = rotateDeviceId(automatic = true, reason = reason)
        if (rotated != null) {
            LanternProxyState.setRecovering("$reason • refreshing device identity")
            if (restartLantern(clearProxyConfig = true, fullConfigRefresh = true)) {
                delay(3_000)
                if (recoveryHealthIsGood()) {
                    recoverySucceeded("Automatic UUID refresh")
                    return
                }
            }
        }

        LanternProxyState.setQuality(LanternQuality.DEGRADED)
        LanternProxyState.setNotice(
            when {
                autoUuidPaused() ->
                    "Still degraded. Auto UUID paused at 10/10 until tomorrow."
                !prefs().getBoolean(PREF_AUTO_UUID, true) ->
                    "Still degraded. Automatic UUID recovery is disabled."
                else -> "Still degraded after all recovery stages."
            }
        )
        promoteToForeground("Lantern degraded • recovery exhausted")
    }

    private suspend fun manualIdentityRefresh() {
        if (!recoveryInProgress.compareAndSet(false, true)) return
        try {
            LanternProxyState.setRecovering("Manual identity refresh")
            rotateDeviceId(automatic = false, reason = "Manual identity refresh")
            val started = restartLantern(
                clearProxyConfig = true,
                fullConfigRefresh = true
            )
            if (started) {
                delay(3_000)
                if (recoveryHealthIsGood()) {
                    recoverySucceeded("Manual UUID refresh")
                } else {
                    LanternProxyState.setQuality(LanternQuality.DEGRADED)
                    LanternProxyState.setNotice(
                        "Manual UUID refresh completed, but connection is still degraded."
                    )
                }
            }
        } finally {
            recoveryCooldownUntil =
                SystemClock.elapsedRealtime() + RECOVERY_COOLDOWN_MS
            recoveryInProgress.set(false)
        }
    }

    private suspend fun restartLantern(
        clearProxyConfig: Boolean,
        fullConfigRefresh: Boolean
    ): Boolean {
        stopLanternProcess(updateState = false)
        if (clearProxyConfig) clearLanternConfig(fullConfigRefresh)
        delay(750)
        return startLantern()
    }

    private suspend fun recoveryHealthIsGood(): Boolean {
        val latency = latencyProbe()
        val speed = miniSpeedProbe()
        val baseline = baselineMbps()

        LanternProxyState.setMetrics(
            latencyMs = latency,
            probeMbps = speed,
            baselineMbps = if (baseline > 0.0) baseline else null
        )

        if (latency == null || speed == null) return false
        if (baseline >= BASELINE_MIN_MBPS) {
            if (
                speed < SLOW_ABSOLUTE_MBPS &&
                speed < baseline * SLOW_RELATIVE_RATIO
            ) return false
        } else if (speed < 1.0) {
            return false
        }
        return true
    }

    private fun recoverySucceeded(method: String) {
        val address = lanProxyAddress("0.0.0.0:$LAN_PORT")
        LanternProxyState.setRunning(address)
        LanternProxyState.setNotice("Recovered automatically: $method")
        promoteToForeground("LAN proxy: $address • recovered")
    }

    private fun latencyProbe(): Long? {
        if (!hasUsableNetwork()) return null
        var connection: HttpURLConnection? = null
        return try {
            val proxy = Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", LAN_PORT)
            )
            connection = URL(
                "https://speed.cloudflare.com/__down?bytes=0"
            ).openConnection(proxy) as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")

            val start = SystemClock.elapsedRealtime()
            val code = connection.responseCode
            val elapsed = SystemClock.elapsedRealtime() - start
            if (code in 200..399) elapsed else null
        } catch (t: Throwable) {
            Log.w(TAG, "Latency probe failed", t)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun miniSpeedProbe(bytes: Int = MINI_PROBE_BYTES): Double? {
        if (!hasUsableNetwork()) return null
        var connection: HttpURLConnection? = null
        return try {
            val proxy = Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", LAN_PORT)
            )
            val url =
                "https://speed.cloudflare.com/__down?bytes=$bytes" +
                    "&r=${SystemClock.elapsedRealtime()}"
            connection = URL(url).openConnection(proxy) as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Connection", "close")

            val start = SystemClock.elapsedRealtime()
            val code = connection.responseCode
            if (code !in 200..399) return null

            var total = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (total < bytes) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
            }

            val elapsedMs =
                (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
            if (total <= 0L) return null

            (total.toDouble() * 8.0 * 1000.0) /
                (elapsedMs.toDouble() * 1_000_000.0)
        } catch (t: Throwable) {
            Log.w(TAG, "Mini speed probe failed", t)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun baselineMbps(): Double {
        synchronized(activeThroughputSamples) {
            if (activeThroughputSamples.isEmpty()) return 0.0
            val sorted = activeThroughputSamples.toList().sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 0) {
                (sorted[middle - 1] + sorted[middle]) / 2.0
            } else {
                sorted[middle]
            }
        }
    }

    private fun currentDeviceId(): String {
        val existing = prefs().getString(PREF_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs().edit().putString(PREF_DEVICE_ID, created).apply()
        return created
    }

    private fun rotateDeviceId(automatic: Boolean, reason: String): String? {
        resetDailyCounterIfNeeded()

        if (automatic) {
            if (!prefs().getBoolean(PREF_AUTO_UUID, true)) {
                syncRecoverySettings()
                return null
            }
            val count = prefs().getInt(PREF_UUID_COUNT, 0)
            if (count >= AUTO_UUID_DAILY_LIMIT) {
                syncRecoverySettings()
                LanternProxyState.setNotice(
                    "Automatic UUID refresh paused: $count/$AUTO_UUID_DAILY_LIMIT used today."
                )
                promoteToForeground("Auto UUID paused ($count/$AUTO_UUID_DAILY_LIMIT)")
                return null
            }
        }

        val newId = UUID.randomUUID().toString()
        val editor = prefs().edit().putString(PREF_DEVICE_ID, newId)
        if (automatic) {
            editor.putInt(PREF_UUID_COUNT, prefs().getInt(PREF_UUID_COUNT, 0) + 1)
        }
        editor.apply()

        Log.i(TAG, "Lantern UUID refreshed automatic=$automatic reason=$reason")
        syncRecoverySettings()

        val count = prefs().getInt(PREF_UUID_COUNT, 0)
        if (automatic && count >= AUTO_UUID_DAILY_LIMIT) {
            LanternProxyState.setNotice(
                "Automatic UUID refresh used $count/$AUTO_UUID_DAILY_LIMIT today. " +
                    "Further automatic UUID changes are paused until tomorrow."
            )
        }
        return newId
    }

    private fun clearLanternConfig(full: Boolean) {
        val dir = File(filesDir, "lantern-free")
        if (!dir.exists()) return

        val names = mutableListOf("proxies.yaml", "proxies.yaml.etag")
        if (full) {
            names += listOf(
                "global.yaml",
                "global.yaml.etag",
                "masquerade_cache"
            )
        }

        names.forEach { name ->
            val target = File(dir, name)
            runCatching {
                if (target.isDirectory) target.deleteRecursively() else target.delete()
            }.onFailure {
                Log.w(TAG, "Unable to clear ${target.name}", it)
            }
        }
    }

    private fun resetDailyCounterIfNeeded() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (prefs().getString(PREF_UUID_DAY, null) == today) return

        prefs().edit()
            .putString(PREF_UUID_DAY, today)
            .putInt(PREF_UUID_COUNT, 0)
            .apply()
        LanternProxyState.setNotice(null)
        syncRecoverySettings()
    }

    private fun autoUuidPaused(): Boolean =
        prefs().getBoolean(PREF_AUTO_UUID, true) &&
            prefs().getInt(PREF_UUID_COUNT, 0) >= AUTO_UUID_DAILY_LIMIT

    private fun syncRecoverySettings() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (prefs().getString(PREF_UUID_DAY, null) != today) {
            prefs().edit()
                .putString(PREF_UUID_DAY, today)
                .putInt(PREF_UUID_COUNT, 0)
                .apply()
        }

        val count = prefs().getInt(PREF_UUID_COUNT, 0)
        val autoUuid = prefs().getBoolean(PREF_AUTO_UUID, true)
        LanternProxyState.setRecoverySettings(
            autoRecoveryEnabled = prefs().getBoolean(PREF_AUTO_RECOVERY, true),
            autoUuidEnabled = autoUuid,
            autoUuidCount = count,
            autoUuidLimit = AUTO_UUID_DAILY_LIMIT,
            autoUuidPaused = autoUuid && count >= AUTO_UUID_DAILY_LIMIT,
            deviceId = currentDeviceId()
        )
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun hasUsableNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun lanProxyAddress(actual: String): String {
        val ip = findLanIpv4()
        return if (ip != null) "$ip:$LAN_PORT" else actual
    }

    private fun findLanIpv4(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
                ?: return@runCatching null
            var fallback: String? = null

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (
                        address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        address.isSiteLocalAddress
                    ) {
                        if (iface.name.startsWith("wlan", ignoreCase = true)) {
                            return@runCatching address.hostAddress
                        }
                        if (fallback == null) fallback = address.hostAddress
                    }
                }
            }
            fallback
        }.getOrNull()
    }

    private fun stopLanternProcess(updateState: Boolean) {
        val process = lanternProcess
        lanternProcess = null
        if (process != null) {
            runCatching { process.destroy() }
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
        if (updateState) LanternProxyState.setStopped()
    }

    private fun hardFail(message: String) {
        Log.e(TAG, message)
        desiredRunning = false
        prefs().edit().putBoolean(PREF_RUNNING, false).apply()
        LanternProxyState.setError(message)
        promoteToForeground("Error: $message")
    }

    private fun promoteToForeground(text: String) {
        val notification = buildNotification(text)
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

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            21,
            Intent(this, LanternProxyService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lantern Free")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun formatMbps(value: Double): String =
        String.format(Locale.US, "%.1f", value)

    override fun onDestroy() {
        desiredRunning = false
        monitorJob?.cancel()
        monitorJob = null
        stopLanternProcess(updateState = false)
        scope.cancel()
        super.onDestroy()
    }
}
