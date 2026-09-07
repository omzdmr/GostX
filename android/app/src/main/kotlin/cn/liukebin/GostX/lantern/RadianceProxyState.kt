package cn.liukebin.gostx.lantern

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RadianceStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class RadianceProxyUiState(
    val status: RadianceStatus = RadianceStatus.STOPPED,
    val stage: String = "Stopped",
    val address: String = "0.0.0.0:8080",
    val socksAddress: String = "127.0.0.1:18080",
    val deviceId: String = "",
    val elapsedMs: Long? = null,
    val lastEvent: String? = null,
    val error: String? = null
)

object RadianceProxyState {
    private val _state = MutableStateFlow(RadianceProxyUiState())
    val state: StateFlow<RadianceProxyUiState> = _state.asStateFlow()

    fun update(block: (RadianceProxyUiState) -> RadianceProxyUiState) {
        _state.value = block(_state.value)
    }

    fun reset() {
        _state.value = RadianceProxyUiState()
    }
}
