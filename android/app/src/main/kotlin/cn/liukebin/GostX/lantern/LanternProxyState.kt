package cn.liukebin.gostx.lantern

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LanternStatus {
    STOPPED,
    STARTING,
    RUNNING,
    RECOVERING,
    ERROR
}

enum class LanternQuality {
    UNKNOWN,
    GOOD,
    SUSPECT,
    DEGRADED
}

data class LanternProxyUiState(
    val status: LanternStatus = LanternStatus.STOPPED,
    val quality: LanternQuality = LanternQuality.UNKNOWN,
    val address: String = "",
    val error: String? = null,
    val notice: String? = null,
    val autoRecoveryEnabled: Boolean = true,
    val autoUuidEnabled: Boolean = true,
    val autoUuidCount: Int = 0,
    val autoUuidLimit: Int = 10,
    val autoUuidPaused: Boolean = false,
    val deviceId: String = "",
    val passiveMbps: Double? = null,
    val baselineMbps: Double? = null,
    val lastLatencyMs: Long? = null,
    val lastProbeMbps: Double? = null,
    val lastRecoveryReason: String? = null
)

object LanternProxyState {
    private val _state = MutableStateFlow(LanternProxyUiState())
    val state: StateFlow<LanternProxyUiState> = _state.asStateFlow()

    fun setStarting() {
        _state.value = _state.value.copy(
            status = LanternStatus.STARTING,
            quality = LanternQuality.UNKNOWN,
            error = null
        )
    }

    fun setRunning(address: String) {
        _state.value = _state.value.copy(
            status = LanternStatus.RUNNING,
            quality = LanternQuality.GOOD,
            address = address,
            error = null
        )
    }

    fun setRecovering(reason: String) {
        _state.value = _state.value.copy(
            status = LanternStatus.RECOVERING,
            quality = LanternQuality.SUSPECT,
            error = null,
            lastRecoveryReason = reason
        )
    }

    fun setStopped() {
        _state.value = _state.value.copy(
            status = LanternStatus.STOPPED,
            quality = LanternQuality.UNKNOWN,
            address = "",
            error = null,
            passiveMbps = null,
            lastLatencyMs = null,
            lastProbeMbps = null
        )
    }

    fun setError(message: String) {
        _state.value = _state.value.copy(
            status = LanternStatus.ERROR,
            quality = LanternQuality.DEGRADED,
            error = message
        )
    }

    fun setQuality(quality: LanternQuality) {
        _state.value = _state.value.copy(quality = quality)
    }

    fun setNotice(message: String?) {
        _state.value = _state.value.copy(notice = message)
    }

    fun setMetrics(
        passiveMbps: Double? = _state.value.passiveMbps,
        baselineMbps: Double? = _state.value.baselineMbps,
        latencyMs: Long? = _state.value.lastLatencyMs,
        probeMbps: Double? = _state.value.lastProbeMbps
    ) {
        _state.value = _state.value.copy(
            passiveMbps = passiveMbps,
            baselineMbps = baselineMbps,
            lastLatencyMs = latencyMs,
            lastProbeMbps = probeMbps
        )
    }

    fun setRecoverySettings(
        autoRecoveryEnabled: Boolean,
        autoUuidEnabled: Boolean,
        autoUuidCount: Int,
        autoUuidLimit: Int,
        autoUuidPaused: Boolean,
        deviceId: String
    ) {
        _state.value = _state.value.copy(
            autoRecoveryEnabled = autoRecoveryEnabled,
            autoUuidEnabled = autoUuidEnabled,
            autoUuidCount = autoUuidCount,
            autoUuidLimit = autoUuidLimit,
            autoUuidPaused = autoUuidPaused,
            deviceId = deviceId
        )
    }
}
