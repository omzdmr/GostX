package cn.liukebin.gostx.secondary

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SecondaryProxyUiState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val listenAddress: String = "",
    val message: String = "Stopped",
    val error: String? = null,
)

object SecondaryProxyState {
    private val mutable = MutableStateFlow(SecondaryProxyUiState())
    val state: StateFlow<SecondaryProxyUiState> = mutable.asStateFlow()

    fun starting(message: String = "Starting...") {
        mutable.value = SecondaryProxyUiState(starting = true, message = message)
    }

    fun running(address: String, message: String = "Running") {
        mutable.value = SecondaryProxyUiState(
            running = true,
            listenAddress = address,
            message = message,
        )
    }

    fun reconnecting(address: String) {
        mutable.value = SecondaryProxyUiState(
            running = true,
            starting = true,
            listenAddress = address,
            message = "Reconnecting...",
        )
    }

    fun stopped() {
        mutable.value = SecondaryProxyUiState(message = "Stopped")
    }

    fun error(message: String) {
        mutable.value = SecondaryProxyUiState(message = "Error", error = message)
    }
}