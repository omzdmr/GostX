package cn.liukebin.gostx.lantern

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import cn.liukebin.gostx.MainActivity
import cn.liukebin.gostx.notification.CHANNEL_ID
import cn.liukebin.gostx.notification.NotificationHelper
import cn.liukebin.gostx.service.GostVpnService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LanternProxyService : Service() {

    companion object {
        const val ACTION_START = "cn.liukebin.gostx.lantern.START"
        const val ACTION_STOP = "cn.liukebin.gostx.lantern.STOP"

        private const val PREFS = "gostx_prefs"
        private const val PREF_RUNNING = "lantern_proxy_running"
        private const val NOTIFICATION_ID = 2
        private const val LAN_PORT = 8080
        private const val TAG = "LanternProxyService"

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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startInProgress = AtomicBoolean(false)

    @Volatile
    private var desiredRunning = false

    @Volatile
    private var lanternProcess: Process? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)

        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_RUNNING, false)) {
            LanternProxyState.setStarting()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                desiredRunning = true
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_RUNNING, true)
                    .apply()

                promoteToForeground("Connecting...")
                scope.launch { startLantern() }
            }

            ACTION_STOP -> {
                desiredRunning = false
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_RUNNING, false)
                    .apply()

                scope.launch {
                    stopLantern()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            null -> {
                val restore = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(PREF_RUNNING, false)
                if (restore) {
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

    private suspend fun startLantern() {
        if (lanternProcess?.isAlive == true) return
        if (!startInProgress.compareAndSet(false, true)) return

        try {
            LanternProxyState.setStarting()
            promoteToForeground("Connecting...")

            // Never leave Gost/TUN running underneath Lantern. The Lantern
            // child process must reach the network directly.
            GostVpnService.stop(this)
            delay(1000)

            val binary = File(applicationInfo.nativeLibraryDir, "liblanternproc.so")
            if (!binary.exists()) {
                fail("Lantern core is missing for this CPU (${Build.SUPPORTED_ABIS.joinToString()})")
                return
            }
            if (!binary.canExecute()) {
                fail("Lantern core is not executable: ${binary.absolutePath}")
                return
            }

            val configDir = File(filesDir, "lantern-free").apply { mkdirs() }

            val builder = ProcessBuilder(
                binary.absolutePath,
                "--config-dir", configDir.absolutePath,
                "--listen", "0.0.0.0:$LAN_PORT",
                "--proxy-all=true"
            )
                .redirectErrorStream(true)

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
                                val addr = line.removePrefix("LANTERN_READY ").trim()
                                LanternProxyState.setRunning(addr)
                                promoteToForeground("LAN proxy: $addr")
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Lantern output reader stopped", it)
                }
            }

            scope.launch {
                val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)

                if (lanternProcess === process) {
                    lanternProcess = null

                    if (desiredRunning) {
                        LanternProxyState.setError(
                            "Lantern core exited ($exitCode). Retrying..."
                        )
                        promoteToForeground("Retrying...")
                        delay(3000)
                        startLantern()
                    } else {
                        LanternProxyState.setStopped()
                    }
                }
            }
        } catch (t: Throwable) {
            fail(t.message ?: t.javaClass.simpleName)
        } finally {
            startInProgress.set(false)
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        desiredRunning = false
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_RUNNING, false)
            .apply()
        LanternProxyState.setError(message)
        promoteToForeground("Error: $message")
    }

    private fun stopLantern() {
        val process = lanternProcess
        lanternProcess = null

        if (process != null) {
            runCatching { process.destroy() }
            if (process.isAlive) {
                runCatching { process.destroyForcibly() }
            }
        }

        LanternProxyState.setStopped()
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

    override fun onDestroy() {
        lanternProcess?.let { process ->
            runCatching { process.destroy() }
        }
        lanternProcess = null
        scope.cancel()
        super.onDestroy()
    }
}