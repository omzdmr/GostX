package cn.liukebin.gostx.ui.lantern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import cn.liukebin.gostx.lantern.LanternProxyService
import cn.liukebin.gostx.lantern.LanternProxyState
import cn.liukebin.gostx.lantern.LanternRadianceService
import cn.liukebin.gostx.lantern.LanternStatus
import cn.liukebin.gostx.lantern.RadianceProxyState
import cn.liukebin.gostx.lantern.RadianceStatus
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanternProxyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val radiance by RadianceProxyState.state.collectAsState()
    val legacy by LanternProxyState.state.collectAsState()

    val radianceBusy = radiance.status == RadianceStatus.STARTING
    val radianceRunning = radiance.status == RadianceStatus.RUNNING
    val legacyRunning = legacy.status == LanternStatus.STARTING ||
        legacy.status == LanternStatus.RUNNING ||
        legacy.status == LanternStatus.RECOVERING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lantern") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Lantern New Core / Radiance", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Resmi Lantern'in guncel Radiance + sing-box motoru. " +
                            "Akilli sunucu secimini Lantern'a birakir; TV yine HTTP :8080 kullanir."
                    )
                    Text("Durum: ${radiance.stage}")
                    if (radiance.status == RadianceStatus.RUNNING) {
                        Text("LAN HTTP proxy: ${radiance.address}", style = MaterialTheme.typography.headlineSmall)
                        Text("Radiance HTTP/SOCKS: ${radiance.socksAddress}")
                    }
                    if (radiance.selectedLocation.isNotBlank()) {
                        Text("Smart Location: ${radiance.selectedLocation}")
                    }
                    if (radiance.selectedProtocol.isNotBlank()) {
                        Text("Protokol: ${radiance.selectedProtocol}")
                    }
                    if (radiance.selectedTag.isNotBlank()) {
                        Text("Sunucu etiketi: ${radiance.selectedTag}", style = MaterialTheme.typography.bodySmall)
                    }
                    radiance.elapsedMs?.let {
                        Text(String.format(Locale.US, "Gecen sure: %.1f sn", it / 1000.0))
                    }
                    radiance.lastEvent?.takeIf { it.isNotBlank() }?.let {
                        Text("Son Lantern olayi: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    radiance.error?.let {
                        Text("Hata: $it", color = MaterialTheme.colorScheme.error)
                    }
                    if (radiance.deviceId.isNotBlank()) {
                        val usageMb = radiance.usageBytes / (1024.0 * 1024.0)
                        Text("Device UUID: ${radiance.deviceId}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            String.format(Locale.US, "UUID sayaci: %.1f / 500 MB", usageMb),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (radiance.rotationCount > 0) {
                            Text("UUID yenileme: ${radiance.rotationCount} kez", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { LanternRadianceService.start(context) },
                            enabled = !radianceBusy && !radianceRunning && !legacyRunning,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (radianceBusy) "Baglaniyor..." else "Radiance Connect") }
                        Button(
                            onClick = { LanternRadianceService.stop(context) },
                            enabled = radianceBusy || radianceRunning,
                            modifier = Modifier.weight(1f)
                        ) { Text("Disconnect") }
                    }
                    Text(
                        "Smart Location ilk baglantida 2-3 dakika surebilir. Her 500 MB alinan trafikte yalnizca Lantern istemci UUID'si yenilenir ve Radiance temizce yeniden baglanir.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Lantern Legacy / Flashlight", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Eski TLS / TLSMasq / Starbridge yolu. A/B karsilastirma icin tutuldu.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Durum: " + when (legacy.status) {
                            LanternStatus.STOPPED -> "Stopped"
                            LanternStatus.STARTING -> "Connecting"
                            LanternStatus.RUNNING -> "Connected"
                            LanternStatus.RECOVERING -> "Recovering"
                            LanternStatus.ERROR -> "Error"
                        }
                    )
                    legacy.passiveMbps?.let {
                        Text("Observed: " + String.format(Locale.US, "%.1f Mbps", it))
                    }
                    legacy.lastProbeMbps?.let {
                        Text("Probe: " + String.format(Locale.US, "%.1f Mbps", it))
                    }
                    legacy.error?.let {
                        Text("Hata: $it", color = MaterialTheme.colorScheme.error)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { LanternProxyService.start(context) },
                            enabled = !legacyRunning && !radianceBusy && !radianceRunning,
                            modifier = Modifier.weight(1f)
                        ) { Text("Legacy Connect") }
                        OutlinedButton(
                            onClick = { LanternProxyService.stop(context) },
                            enabled = legacyRunning,
                            modifier = Modifier.weight(1f)
                        ) { Text("Stop Legacy") }
                    }
                }
            }

            Text(
                "Test icin once Radiance'i kullan. Baglandiginda TV proxy adresi eskisiyle ayni: " +
                    "telefonun Wi-Fi IP'si, port 8080.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
