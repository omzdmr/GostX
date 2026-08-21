package cn.liukebin.gostx

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import cn.liukebin.gostx.data.ConfigRepository
import cn.liukebin.gostx.ui.config.ConfigScreen
import org.junit.Rule
import org.junit.Test

class ConfigScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun repo(): ConfigRepository {
        val prefs = context().getSharedPreferences("config_screen_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return ConfigRepository(prefs)
    }

    private fun repoWithProfile(yaml: String = ""): Pair<ConfigRepository, String> {
        val r = repo()
        val id = r.addProfile("Test Profile")!!
        r.saveConfig(id, yaml)
        return r to id
    }

    @Test fun yamlEditorShowsSavedContent() {
        val (repo, id) = repoWithProfile(yaml = "services:\n  - demo")
        rule.setContent { ConfigScreen(repo = repo, profileId = id, onBack = {}) }
        rule.onNodeWithText("services:", substring = true).assertIsDisplayed()
    }

    @Test fun saveButtonIsVisible() {
        val (repo, id) = repoWithProfile()
        rule.setContent { ConfigScreen(repo = repo, profileId = id, onBack = {}) }
        rule.onNodeWithContentDescription(
            context().getString(R.string.action_save)
        ).assertIsDisplayed()
    }

    @Test fun renameButtonIsVisible() {
        val (repo, id) = repoWithProfile()
        rule.setContent { ConfigScreen(repo = repo, profileId = id, onBack = {}) }
        rule.onNodeWithContentDescription(
            context().getString(R.string.profile_rename)
        ).assertIsDisplayed()
    }
}
