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
import androidx.compose.material3.Scaffold
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
import cn.liukebin.gostx.lantern.LanternStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanternProxyScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by LanternProxyState.state.collectAsState()

    val isStarting = state.status == LanternStatus.STARTING
    val isRunning = state.status == LanternStatus.RUNNING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lantern Free") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                            LanternStatus.ERROR -> "Error"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (isRunning) {
                        Text("LAN HTTP proxy")
                        Text(
                            text = state.address.ifBlank { "0.0.0.0:8080" },
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            "On the TV, use this phone's Wi-Fi IP address with port 8080."
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
                text = "This test build currently packages the Lantern core for arm64-v8a only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}