package cn.liukebin.gostx.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.liukebin.gostx.service.GostVpnService
import cn.liukebin.gostx.secondary.SecondaryProxyService
import cn.liukebin.gostx.anycast.AnycastAutoManager
import cn.liukebin.gostx.anycast.AnycastConfigBuilder
import cn.liukebin.gostx.data.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE)
        val secondaryWasRunning = prefs.getBoolean("secondary_proxy_running", false)
        val wasRunning = prefs.getBoolean("last_vpn_running", false)

        if (secondaryWasRunning) {
            SecondaryProxyService.start(context)
            return
        }

        if (wasRunning) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val repo = ConfigRepository(context.getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE))
                    if (repo.getActiveProfileId() == AnycastConfigBuilder.PROFILE_ID) {
                        runCatching { AnycastAutoManager(context).refreshActiveProfile(repo) }
                    }
                    GostVpnService.start(context)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
