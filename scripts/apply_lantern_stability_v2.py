from pathlib import Path

SERVICE = Path('android/app/src/main/kotlin/cn/liukebin/GostX/lantern/LanternProxyService.kt')


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f'Lantern stability patch: missing {label}')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'Lantern stability patch: {label} expected once, found {count}')
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    a = text.find(start)
    if a < 0:
        raise SystemExit(f'Lantern stability patch: missing start marker for {label}')
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f'Lantern stability patch: missing end marker for {label}')
    return text[:a] + replacement.rstrip() + '\n\n' + text[b:]


text = SERVICE.read_text(encoding='utf-8')

# Policy: recovery should be sticky and conservative. A noisy probe must not
# burn device identities. UUID is the last resort, never the first hammer.
text = replace_once(
    text,
    '        private const val PREF_UUID_COUNT = "lantern_uuid_count"\n',
    '        private const val PREF_UUID_COUNT = "lantern_uuid_count"\n'
    '        private const val PREF_DEVICE_ID_CREATED_AT = "lantern_device_id_created_at"\n'
    '        private const val PREF_LAST_AUTO_UUID_AT = "lantern_last_auto_uuid_at"\n'
    '        private const val PREF_LAST_UUID_REASON = "lantern_last_uuid_reason"\n',
    'identity preference keys'
)

text = replace_once(text, '        const val AUTO_UUID_DAILY_LIMIT = 10\n',
                    '        const val AUTO_UUID_DAILY_LIMIT = 2\n', 'daily UUID limit')
text = replace_once(text, '        private const val HEALTH_INTERVAL_MS = 30_000L\n',
                    '        private const val HEALTH_INTERVAL_MS = 45_000L\n', 'health interval')
text = replace_once(text, '        private const val RECOVERY_COOLDOWN_MS = 120_000L\n',
                    '        private const val RECOVERY_COOLDOWN_MS = 600_000L\n', 'recovery cooldown')
text = replace_once(text, '        private const val PASSIVE_BAD_SAMPLES = 3\n',
                    '        private const val PASSIVE_BAD_SAMPLES = 12\n', 'passive bad samples')
text = replace_once(text, '        private const val HEALTH_BAD_SAMPLES = 2\n',
                    '        private const val HEALTH_BAD_SAMPLES = 4\n', 'health bad samples')
text = replace_once(text, '        private const val MINI_PROBE_BYTES = 262_144\n',
                    '        private const val MINI_PROBE_BYTES = 1_048_576\n'
                    '        private const val RECOVERY_CYCLES_BEFORE_UUID = 2\n'
                    '        private const val MIN_AUTO_UUID_AGE_MS = 6L * 60L * 60L * 1000L\n'
                    '        private const val MIN_AUTO_UUID_INTERVAL_MS = 6L * 60L * 60L * 1000L\n',
                    'stability constants')

text = replace_once(
    text,
    '    private var passiveBadSamples = 0\n    private var healthBadSamples = 0\n',
    '    private var passiveBadSamples = 0\n'
    '    private var healthBadSamples = 0\n'
    '    private var exhaustedRecoveryCycles = 0\n',
    'recovery cycle counter'
)

old_health = '''                    if (latency == null || latency >= LATENCY_SUSPECT_MS) {
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
'''
new_health = '''                    if (latency == null || latency >= LATENCY_SUSPECT_MS) {
                        healthBadSamples++
                        LanternProxyState.setQuality(LanternQuality.SUSPECT)
                        if (healthBadSamples >= HEALTH_BAD_SAMPLES) {
                            healthBadSamples = 0
                            confirmHealthDegradation(
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
'''
text = replace_once(text, old_health, new_health, 'health degradation confirmation')

insert_marker = '    private fun confirmPassiveSlowdown(baseline: Double) {'
require(text, insert_marker, 'confirmPassiveSlowdown marker')
health_helper = '''    private fun confirmHealthDegradation(reason: String) {
        if (!confirmInProgress.compareAndSet(false, true)) return

        scope.launch {
            try {
                if (!desiredRunning || recoveryInProgress.get()) return@launch

                val baseline = baselineMbps()
                val first = miniSpeedProbe()
                LanternProxyState.setMetrics(probeMbps = first)
                delay(10_000)
                val second = miniSpeedProbe()
                LanternProxyState.setMetrics(probeMbps = second)

                if (probeIsSlow(first, baseline) && probeIsSlow(second, baseline)) {
                    triggerRecovery(reason)
                } else {
                    LanternProxyState.setQuality(LanternQuality.GOOD)
                    LanternProxyState.setNotice(
                        "Health probe recovered; keeping current Lantern route and UUID."
                    )
                }
            } finally {
                confirmInProgress.set(false)
            }
        }
    }

'''
text = text.replace(insert_marker, health_helper + insert_marker, 1)

text = replace_between(
    text,
    '    private fun probeIsSlow(speed: Double?, baseline: Double): Boolean {',
    '    private fun triggerRecovery(reason: String) {',
    '''    private fun probeIsSlow(speed: Double?, baseline: Double): Boolean {
        if (speed == null) return true
        if (baseline < BASELINE_MIN_MBPS) return speed < 1.0
        val floor = maxOf(SLOW_ABSOLUTE_MBPS, baseline * SLOW_RELATIVE_RATIO)
        return speed < floor
    }''',
    'probeIsSlow'
)

text = replace_between(
    text,
    '    private suspend fun performRecovery(reason: String) {',
    '    private suspend fun manualIdentityRefresh() {',
    '''    private suspend fun performRecovery(reason: String) {
        if (!desiredRunning) return
        if (!hasUsableNetwork()) {
            LanternProxyState.setNotice("Recovery postponed: phone network is unavailable.")
            return
        }

        LanternProxyState.setRecovering(reason)
        promoteToForeground("Recovering: $reason")

        // Stage 1: reconnect without touching the assigned proxy/config pool.
        if (restartLantern(clearProxyConfig = false, fullConfigRefresh = false)) {
            delay(8_000)
            if (recoveryHealthIsStable()) {
                recoverySucceeded("Sticky process restart")
                return
            }
        }

        // Stage 2: ask Lantern for a fresh server/config pool, but keep identity.
        LanternProxyState.setRecovering("$reason • refreshing server config")
        if (restartLantern(clearProxyConfig = true, fullConfigRefresh = false)) {
            delay(15_000)
            if (recoveryHealthIsStable()) {
                recoverySucceeded("Server/config refresh")
                return
            }
        }

        // One failed recovery cycle is not enough reason to burn a UUID. Wait for
        // another independently-confirmed failure after the cooldown.
        exhaustedRecoveryCycles++
        if (exhaustedRecoveryCycles < RECOVERY_CYCLES_BEFORE_UUID) {
            LanternProxyState.setQuality(LanternQuality.DEGRADED)
            LanternProxyState.setNotice(
                "Still degraded after reconnect + config refresh. Keeping UUID; " +
                    "will retry before identity fallback."
            )
            promoteToForeground("Lantern degraded • UUID preserved")
            return
        }

        // Stage 3: identity rotation is the final fallback and is rate/age limited.
        val rotated = rotateDeviceId(automatic = true, reason = reason)
        if (rotated != null) {
            exhaustedRecoveryCycles = 0
            LanternProxyState.setRecovering("$reason • refreshing device identity")
            if (restartLantern(clearProxyConfig = true, fullConfigRefresh = true)) {
                delay(15_000)
                if (recoveryHealthIsStable()) {
                    recoverySucceeded("Automatic UUID refresh")
                    return
                }
            }
        }

        LanternProxyState.setQuality(LanternQuality.DEGRADED)
        val block = automaticUuidBlockReason()
        LanternProxyState.setNotice(
            if (block != null) {
                "Still degraded. UUID preserved: $block"
            } else {
                "Still degraded after all recovery stages."
            }
        )
        promoteToForeground("Lantern degraded • recovery exhausted")
    }''',
    'performRecovery'
)

# Replace single-sample recovery verdict with a better relative threshold and add
# a two-sample stable verdict used between escalation stages.
text = replace_between(
    text,
    '    private suspend fun recoveryHealthIsGood(): Boolean {',
    '    private fun recoverySucceeded(method: String) {',
    '''    private suspend fun recoveryHealthIsGood(): Boolean {
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
            val floor = maxOf(SLOW_ABSOLUTE_MBPS, baseline * SLOW_RELATIVE_RATIO)
            if (speed < floor) return false
        } else if (speed < 1.0) {
            return false
        }
        return true
    }

    private suspend fun recoveryHealthIsStable(): Boolean {
        if (!recoveryHealthIsGood()) return false
        delay(10_000)
        return recoveryHealthIsGood()
    }''',
    'recovery health verdict'
)

text = replace_once(
    text,
    '    private fun recoverySucceeded(method: String) {\n        val address = lanProxyAddress("0.0.0.0:$LAN_PORT")\n',
    '    private fun recoverySucceeded(method: String) {\n'
    '        exhaustedRecoveryCycles = 0\n'
    '        val address = lanProxyAddress("0.0.0.0:$LAN_PORT")\n',
    'recovery success reset'
)

text = replace_between(
    text,
    '    private fun currentDeviceId(): String {',
    '    private fun rotateDeviceId(automatic: Boolean, reason: String): String? {',
    '''    private fun currentDeviceId(): String {
        val existing = prefs().getString(PREF_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            if (prefs().getLong(PREF_DEVICE_ID_CREATED_AT, 0L) <= 0L) {
                prefs().edit()
                    .putLong(PREF_DEVICE_ID_CREATED_AT, System.currentTimeMillis())
                    .putString(PREF_LAST_UUID_REASON, "Existing identity adopted by stability policy")
                    .apply()
            }
            return existing
        }

        val created = UUID.randomUUID().toString()
        prefs().edit()
            .putString(PREF_DEVICE_ID, created)
            .putLong(PREF_DEVICE_ID_CREATED_AT, System.currentTimeMillis())
            .putString(PREF_LAST_UUID_REASON, "Initial identity")
            .apply()
        return created
    }''',
    'currentDeviceId'
)

text = replace_between(
    text,
    '    private fun rotateDeviceId(automatic: Boolean, reason: String): String? {',
    '    private fun clearLanternConfig(full: Boolean) {',
    '''    private fun rotateDeviceId(automatic: Boolean, reason: String): String? {
        resetDailyCounterIfNeeded()

        if (automatic) {
            val block = automaticUuidBlockReason()
            if (block != null) {
                syncRecoverySettings()
                LanternProxyState.setNotice("Automatic UUID refresh skipped: $block")
                promoteToForeground("UUID preserved • $block")
                return null
            }
        }

        val now = System.currentTimeMillis()
        val newId = UUID.randomUUID().toString()
        val editor = prefs().edit()
            .putString(PREF_DEVICE_ID, newId)
            .putLong(PREF_DEVICE_ID_CREATED_AT, now)
            .putString(PREF_LAST_UUID_REASON, reason)

        if (automatic) {
            editor
                .putInt(PREF_UUID_COUNT, prefs().getInt(PREF_UUID_COUNT, 0) + 1)
                .putLong(PREF_LAST_AUTO_UUID_AT, now)
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

    private fun automaticUuidBlockReason(): String? {
        if (!prefs().getBoolean(PREF_AUTO_UUID, true)) {
            return "automatic UUID recovery is disabled"
        }

        val count = prefs().getInt(PREF_UUID_COUNT, 0)
        if (count >= AUTO_UUID_DAILY_LIMIT) {
            return "daily UUID limit reached ($count/$AUTO_UUID_DAILY_LIMIT)"
        }

        val now = System.currentTimeMillis()
        val createdAt = prefs().getLong(PREF_DEVICE_ID_CREATED_AT, 0L)
        if (createdAt > 0L) {
            val age = (now - createdAt).coerceAtLeast(0L)
            if (age < MIN_AUTO_UUID_AGE_MS) {
                return "current UUID is younger than 6 hours"
            }
        }

        val lastAuto = prefs().getLong(PREF_LAST_AUTO_UUID_AT, 0L)
        if (lastAuto > 0L && now - lastAuto < MIN_AUTO_UUID_INTERVAL_MS) {
            return "last automatic UUID change was less than 6 hours ago"
        }

        return null
    }''',
    'rotateDeviceId'
)

# Keep the UI/status wording dynamic now that the limit is intentionally small.
text = text.replace('"Still degraded. Auto UUID paused at 10/10 until tomorrow."',
                    '"Still degraded. Auto UUID paused until tomorrow."')

SERVICE.write_text(text, encoding='utf-8')
print('Lantern stability v2 applied successfully')
