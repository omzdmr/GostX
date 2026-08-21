# 重写 androidTest 套件 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重写 HomeScreenTest 与 ConfigScreenTest，使断言匹配当前 UI（资源字符串断言、消除 locale 依赖），并让 `connectedAndroidTest` 全绿。

**Architecture:** 两个测试文件按设计文档中的新测试规格整体替换；断言全部基于 `InstrumentationRegistry` 的资源字符串；测试用独立 SharedPreferences + `addProfile` 构造有 profile 的 repo。验证链：编译 androidTest → 设备 connectedAndroidTest 全绿。

**Tech Stack:** Compose UI Test（createComposeRule）、JUnit4、AGP 8.7.3、设备 RFCX11HLKAZ

## Global Constraints

- **只改两个 androidTest 文件**：`HomeScreenTest.kt`、`ConfigScreenTest.kt`，main 源码一律不动
- **断言必须用资源字符串**（`R.string.*`），禁止硬编码中文/英文文案
- **不新增依赖**、不引入 mock 框架
- 测试构造 repo 必须 `prefs.edit().clear().commit()` 隔离状态
- 本仓库约定不代用户提交 git（无 commit 步骤）
- Gradle 命令在 `android/` 下执行，bash timeout ≥ 1800s（设备测试较慢）

---

### Task 1: 重写 HomeScreenTest.kt

**Files:**
- Modify: `android/app/src/androidTest/kotlin/cn/liukebin/GostX/HomeScreenTest.kt`（整体替换）

**Interfaces:**
- Consumes: `HomeScreen(repo, ...)`（repo 必填）、`ConfigRepository(prefs)`、`ConfigRepository.addProfile(name): String?`、`GlobalVpnState.setState(VpnState)`、资源 `vpn_start_label` / `vpn_stop_label` / `home_empty_profiles`
- Produces: 4 个通过测试（启动按钮/停止按钮/profile 列表/空状态）

- [ ] **Step 1: 替换文件内容**

完整内容（整文件）：

```kotlin
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
```

- [ ] **Step 2: 编译验证**

```bash
cd android && ./gradlew :app:compileDebugAndroidTestKotlin --console=plain
```

预期：`BUILD SUCCESSFUL`（无编译错误）。

- [ ] **Step 3: 运行 HomeScreenTest（设备）**

```bash
cd android && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.liukebin.gostx.HomeScreenTest --console=plain
```

预期：4/4 通过（设备 RFCX11HLKAZ 已连接）。

---

### Task 2: 重写 ConfigScreenTest.kt

**Files:**
- Modify: `android/app/src/androidTest/kotlin/cn/liukebin/GostX/ConfigScreenTest.kt`（整体替换）

**Interfaces:**
- Consumes: `ConfigScreen(repo, profileId, onBack)`、`ConfigRepository.saveConfig(id, yaml)`、资源 `action_save` / `profile_rename`
- Produces: 3 个通过测试（YAML 内容/保存按钮/重命名按钮）

- [ ] **Step 1: 替换文件内容**

完整内容（整文件）：

```kotlin
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
```

- [ ] **Step 2: 编译验证**

```bash
cd android && ./gradlew :app:compileDebugAndroidTestKotlin --console=plain
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 3: 运行 ConfigScreenTest（设备）**

```bash
cd android && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.liukebin.gostx.ConfigScreenTest --console=plain
```

预期：3/3 通过。

---

### Task 3: 全量设备测试验证

- [ ] **Step 1: 运行完整 connectedAndroidTest**

```bash
cd android && ./gradlew connectedAndroidTest --console=plain
```

预期：HomeScreenTest 4/4 + ConfigScreenTest 3/4... 不，预期 4+3=7/7 全部通过，`BUILD SUCCESSFUL`。

- [ ] **Step 2: 检查报告**

```bash
ls android/app/build/reports/androidTests/connected/
# 确认 index.html 显示 0 failures
```

- [ ] **Step 3: 复跑单元测试确认无回归**

```bash
cd android && ./gradlew test --console=plain
```

预期：BUILD SUCCESSFUL（unit test 不受影响，确认无回归）。

---

## 成功标准（对照设计文档）

- [ ] connectedAndroidTest 全绿（7/7）
- [ ] 断言全部基于资源字符串，无硬编码中文
- [ ] 单元测试无回归

## 回滚方案

两个测试文件均整体替换，回滚 = `git checkout -- <file>`（由用户执行，仓库约定不代提交）。
