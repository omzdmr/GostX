package cn.liukebin.gostx.ui.secondary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.liukebin.gostx.secondary.SecondaryProxyService
import cn.liukebin.gostx.secondary.SecondaryProxyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondaryProxyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by SecondaryProxyState.state.collectAsState()

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (state.running) "LAN proxy active" else state.message,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    if (state.listenAddress.isNotBlank()) {
                        Text("TV HTTP proxy")
                        Text(
                            state.listenAddress,
                            style = MaterialTheme.typography.headlineSmall
                        )
                    } else {
                        Text("Port: ${SecondaryProxyService.LAN_PORT}")
                    }
                    if (state.error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.error!!,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Text(
                "Lantern uses its normal free session and keeps its configuration in app storage. " +
                    "If the connection dies, GostX retries the same session automatically."
            )

            if (state.running || state.starting) {
                OutlinedButton(
                    onClick = { SecondaryProxyService.stop(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Stop Lantern")
                }
            } else {
                Button(
                    onClick = { SecondaryProxyService.start(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Lantern Free")
                }
            }

            Text(
                "On the TV, keep HTTP proxy set to this phone's Wi-Fi IP and port " +
                    "${SecondaryProxyService.LAN_PORT}. Do not run the primary VPN and Lantern at the same time."
            )
        }
    }
}