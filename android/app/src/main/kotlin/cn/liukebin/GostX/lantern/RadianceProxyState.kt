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
    val socksAddress: String = "0.0.0.0:8080",
    val deviceId: String = "",
    val selectedTag: String = "",
    val selectedProtocol: String = "",
    val selectedLocation: String = "",
    val usageBytes: Long = 0L,
    val rotationCount: Int = 0,
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
