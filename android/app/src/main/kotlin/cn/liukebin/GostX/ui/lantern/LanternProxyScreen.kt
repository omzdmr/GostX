package cn.liukebin.gostx.ui.lantern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.liukebin.gostx.lantern.LanternProxyService
import cn.liukebin.gostx.lantern.LanternProxyState
import cn.liukebin.gostx.lantern.LanternQuality
import cn.liukebin.gostx.lantern.LanternStatus
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanternProxyScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by LanternProxyState.state.collectAsState()

    val isStarting = state.status == LanternStatus.STARTING
    val isRecovering = state.status == LanternStatus.RECOVERING
    val isRunning =
        state.status == LanternStatus.RUNNING ||
            state.status == LanternStatus.RECOVERING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lantern Free") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = when (state.status) {
                            LanternStatus.STOPPED -> "Stopped"
                            LanternStatus.STARTING -> "Connecting..."
                            LanternStatus.RUNNING -> "Connected"
                            LanternStatus.RECOVERING -> "Recovering..."
                            LanternStatus.ERROR -> "Error"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        "Quality: " + when (state.quality) {
                            LanternQuality.UNKNOWN -> "Unknown"
                            LanternQuality.GOOD -> "Good"
                            LanternQuality.SUSPECT -> "Checking..."
                            LanternQuality.DEGRADED -> "Degraded"
                        }
                    )

                    if (isRunning) {
                        Text("LAN HTTP proxy")
                        Text(
                            text = state.address.ifBlank { "0.0.0.0:8080" },
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            "TV tarafinda ayni telefon Wi-Fi IP'sini ve 8080 portunu kullan."
                        )
                    }

                    state.passiveMbps?.let {
                        Text(
                            "Observed traffic: " +
                                String.format(Locale.US, "%.1f Mbps", it)
                        )
                    }
                    state.baselineMbps?.let {
                        Text(
                            "Learned baseline: " +
                                String.format(Locale.US, "%.1f Mbps", it)
                        )
                    }
                    state.lastLatencyMs?.let {
                        Text("Proxy latency: $it ms")
                    }
                    state.lastProbeMbps?.let {
                        Text(
                            "Last confirm probe: " +
                                String.format(Locale.US, "%.1f Mbps", it)
                        )
                    }
                    state.lastRecoveryReason?.let {
                        Text("Last recovery: $it")
                    }
                    state.notice?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    state.error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatic recovery")
                            Text(
                                "Restart -> server/config refresh -> UUID fallback",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = state.autoRecoveryEnabled,
                            onCheckedChange = {
                                LanternProxyService.setAutoRecovery(context, it)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatic UUID fallback")
                            Text(
                                if (state.autoUuidPaused) {
                                    "Paused until tomorrow"
                                } else {
                                    "Only after normal recovery fails"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = state.autoUuidEnabled,
                            enabled = state.autoRecoveryEnabled,
                            onCheckedChange = {
                                LanternProxyService.setAutoUuidRecovery(context, it)
                            }
                        )
                    }

                    Text(
                        "Automatic UUID refresh today: " +
                            "${state.autoUuidCount}/${state.autoUuidLimit}"
                    )

                    if (state.deviceId.isNotBlank()) {
                        Text(
                            "Device UUID: ${state.deviceId}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            LanternProxyService.refreshIdentity(context)
                        },
                        enabled = !isStarting && !isRecovering,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Refresh UUID now")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { LanternProxyService.start(context) },
                    enabled = !isStarting && !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isStarting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(2.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Connect")
                    }
                }

                Button(
                    onClick = { LanternProxyService.stop(context) },
                    enabled = isStarting || isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Disconnect")
                }
            }

            Text(
                text =
                    "Normal hiz olcumu Android UID trafik sayacindan pasif yapilir. " +
                        "Ek 256 KB test sadece dramatik dusus supheliyse dogrulama icin calisir.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
