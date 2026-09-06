package cn.liukebin.gostx.ui.anycast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.liukebin.gostx.anycast.AnycastAutoManager
import cn.liukebin.gostx.anycast.AnycastNode
import cn.liukebin.gostx.data.ConfigRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnycastScreen(
    repo: ConfigRepository,
    onBack: () -> Unit,
    onRequestVpnPermission: () -> Unit,
) {
    val context = LocalContext.current
    val manager = remember { AnycastAutoManager(context) }
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nodes by remember { mutableStateOf<List<AnycastNode>>(emptyList()) }
    var selectedId by remember { mutableStateOf(manager.selectedNodeId()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (manager.hasCredentials()) "Saved account found" else "Enter your Anycast account") }

    fun refresh(forceLogin: Boolean = false) {
        scope.launch {
            busy = true
            status = "Loading servers…"
            runCatching { manager.refreshNodes(forceLogin) }
                .onSuccess {
                    nodes = it
                    if (selectedId == null) selectedId = it.firstOrNull()?.idName
                    status = "${it.size} servers loaded"
                }
                .onFailure { status = it.message ?: "Failed to load servers" }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (manager.hasCredentials()) refresh(false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anycast Auto") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { if (!busy) refresh(true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!manager.hasCredentials() || nodes.isEmpty()) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Anycast email") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Anycast password") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Button(
                    onClick = {
                        if (email.isNotBlank() && password.isNotBlank()) {
                            manager.saveCredentials(email.trim(), password)
                            refresh(true)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Login & load servers") }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(status, style = MaterialTheme.typography.bodyMedium)
                if (busy) CircularProgressIndicator(modifier = Modifier.height(20.dp))
            }

            if (nodes.isNotEmpty()) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(nodes, key = { it.idName }) { node ->
                        ListItem(
                            headlineContent = { Text(node.name) },
                            supportingContent = {
                                Text(listOf(node.country.uppercase(), node.region, node.idName).filter { it.isNotBlank() }.joinToString(" • "))
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = selectedId == node.idName,
                                    onClick = {
                                        selectedId = node.idName
                                        manager.selectNode(node.idName)
                                    }
                                )
                            },
                            modifier = Modifier.clickable {
                                selectedId = node.idName
                                manager.selectNode(node.idName)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        val id = selectedId ?: return@Button
                        manager.selectNode(id)
                        scope.launch {
                            busy = true
                            status = "Refreshing token and connecting…"
                            runCatching { manager.refreshActiveProfile(repo) }
                                .onSuccess {
                                    status = "Connecting to ${it.name}"
                                    onRequestVpnPermission()
                                }
                                .onFailure { status = it.message ?: "Connection setup failed" }
                            busy = false
                        }
                    },
                    enabled = !busy && selectedId != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect") }
            }
        }
    }
}
