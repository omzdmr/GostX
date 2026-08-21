package cn.liukebin.gostx

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import cn.liukebin.gostx.data.ConfigRepository
import cn.liukebin.gostx.data.GlobalVpnState
import cn.liukebin.gostx.data.VpnState
import cn.liukebin.gostx.data.VpnStatus
import cn.liukebin.gostx.ui.home.HomeScreen
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun repo(): ConfigRepository {
        val prefs = context().getSharedPreferences("home_screen_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return ConfigRepository(prefs)
    }

    private fun repoWithProfile(): ConfigRepository {
        val r = repo()
        r.addProfile("Profile 1")
        return r
    }

    @Test fun showsStartButtonWhenStopped() {
        GlobalVpnState.setState(VpnState(VpnStatus.STOPPED))
        rule.setContent { HomeScreen(repo = repoWithProfile()) }
        rule.onNodeWithContentDescription(
            context().getString(R.string.vpn_start_label)
        ).assertIsDisplayed()
    }

    @Test fun showsStopButtonWhenConnected() {
        GlobalVpnState.setState(VpnState(VpnStatus.CONNECTED, "127.0.0.1:10808"))
        rule.setContent { HomeScreen(repo = repoWithProfile()) }
        rule.onNodeWithContentDescription(
            context().getString(R.string.vpn_stop_label)
        ).assertIsDisplayed()
    }

    @Test fun profileNameShownInList() {
        GlobalVpnState.setState(VpnState(VpnStatus.STOPPED))
        rule.setContent { HomeScreen(repo = repoWithProfile()) }
        rule.onNodeWithText("Profile 1").assertIsDisplayed()
    }

    @Test fun emptyStateWhenNoProfiles() {
        GlobalVpnState.setState(VpnState(VpnStatus.STOPPED))
        rule.setContent { HomeScreen(repo = repo()) }
        rule.onNodeWithText(
            context().getString(R.string.home_empty_profiles),
            substring = true
        ).assertIsDisplayed()
        rule.onNodeWithContentDescription(
            context().getString(R.string.vpn_start_label)
        ).assertDoesNotExist()
    }
}
