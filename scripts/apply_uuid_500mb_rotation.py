from pathlib import Path

SERVICE = Path('android/app/src/main/kotlin/cn/liukebin/GostX/lantern/LanternProxyService.kt')
text = SERVICE.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'UUID 500MB patch: {label} expected once, found {count}')
    text = text.replace(old, new, 1)


# Persist usage across process restarts/reboots. The counter is only proxy-service
# UID receive traffic observed while Lantern is actively running.
replace_once(
    '        private const val PREF_LAST_UUID_REASON = "lantern_last_uuid_reason"\n',
    '        private const val PREF_LAST_UUID_REASON = "lantern_last_uuid_reason"\n'
    '        private const val PREF_UUID_USAGE_BYTES = "lantern_uuid_usage_bytes"\n',
    'usage preference key'
)

replace_once(
    '        private const val MIN_AUTO_UUID_INTERVAL_MS = 6L * 60L * 60L * 1000L\n',
    '        private const val MIN_AUTO_UUID_INTERVAL_MS = 6L * 60L * 60L * 1000L\n'
    '        private const val UUID_ROTATE_AFTER_BYTES = 500L * 1024L * 1024L\n',
    '500 MB threshold constant'
)

replace_once(
    '    private val confirmInProgress = AtomicBoolean(false)\n',
    '    private val confirmInProgress = AtomicBoolean(false)\n'
    '    private val usageRotationInProgress = AtomicBoolean(false)\n',
    'usage rotation guard'
)

replace_once(
    '                    updatePassiveThroughput(passiveMbps)\n',
    '                    updatePassiveThroughput(passiveMbps)\n'
    '                    if (deltaBytes > 0L) {\n'
    '                        accumulateUsageAndMaybeRotate(deltaBytes)\n'
    '                    }\n',
    'traffic accumulator hook'
)

marker = '    private fun updatePassiveThroughput(mbps: Double) {'
if marker not in text:
    raise SystemExit('UUID 500MB patch: updatePassiveThroughput marker missing')

helper = '''    private fun accumulateUsageAndMaybeRotate(deltaBytes: Long) {
        if (deltaBytes <= 0L || !desiredRunning) return

        val previous = prefs().getLong(PREF_UUID_USAGE_BYTES, 0L).coerceAtLeast(0L)
        val total = previous + deltaBytes
        if (total < UUID_ROTATE_AFTER_BYTES) {
            prefs().edit().putLong(PREF_UUID_USAGE_BYTES, total).apply()
            return
        }

        // Preserve any bytes beyond the threshold so repeated 500 MB boundaries
        // stay accurate rather than drifting after a large sample.
        val remainder = total % UUID_ROTATE_AFTER_BYTES
        prefs().edit().putLong(PREF_UUID_USAGE_BYTES, remainder).apply()

        if (!usageRotationInProgress.compareAndSet(false, true)) return

        scope.launch {
            try {
                if (!desiredRunning) return@launch

                LanternProxyState.setRecovering("500 MB used • refreshing device identity")
                promoteToForeground("500 MB used • refreshing UUID")

                val rotated = rotateDeviceId(
                    automatic = false,
                    reason = "500 MB traffic threshold"
                )

                if (rotated != null && desiredRunning) {
                    // A new identity should start with a clean Lantern config so the
                    // server assignment/auth state is fetched consistently.
                    restartLantern(clearProxyConfig = true, fullConfigRefresh = true)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "500 MB UUID rotation failed", t)
                LanternProxyState.setNotice("500 MB UUID refresh failed; current service kept running.")
            } finally {
                usageRotationInProgress.set(false)
            }
        }
    }

'''
text = text.replace(marker, helper + marker, 1)

SERVICE.write_text(text, encoding='utf-8')
print('500 MB Lantern UUID rotation policy applied successfully')
