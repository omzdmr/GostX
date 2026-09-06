package cn.liukebin.gostx.lantern

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LanternStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class LanternProxyUiState(
    val status: LanternStatus = LanternStatus.STOPPED,
    val address: String = "",
    val error: String? = null
)

object LanternProxyState {
    private val _state = MutableStateFlow(LanternProxyUiState())
    val state: StateFlow<LanternProxyUiState> = _state.asStateFlow()

    fun setStarting() {
        _state.value = LanternProxyUiState(status = LanternStatus.STARTING)
    }

    fun setRunning(address: String) {
        _state.value = LanternProxyUiState(
            status = LanternStatus.RUNNING,
            address = address
        )
    }

    fun setStopped() {
        _state.value = LanternProxyUiState()
    }

    fun setError(message: String) {
        _state.value = LanternProxyUiState(
            status = LanternStatus.ERROR,
            error = message
        )
    }
}