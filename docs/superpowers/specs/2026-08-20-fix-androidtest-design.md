# 重写 androidTest 套件（匹配当前 UI）— 设计文档

日期：2026-08-20
状态：已批准（用户确认方案）

## 背景

GostX 的 androidTest（HomeScreenTest / ConfigScreenTest）与当前 UI 严重漂移：
- 编译错误已修复（Task 3.5，签名对齐）
- 但运行时 6/6 失败：断言指向已不存在的 UI 元素（详见下文诊断）

## 诊断（当前 UI 实际状态）

| 旧断言 | 当前 UI 事实 |
|---|---|
| `onNodeWithText("启动 VPN")` | 启动/停止是 **FAB 的 contentDescription**（`vpn_start_label`/`vpn_stop_label`），且**仅当 repo 有 profile 时渲染** |
| `onNodeWithText("停止 VPN")` | 同上（CONNECTED 时 contentDescription 切换） |
| `onNodeWithText("监听: 127.0.0.1:10808")` | HomeScreen **不再显示**监听地址（仅存在于通知 `NotificationHelper`） |
| `onNodeWithText("验证")` | ConfigScreen **无验证按钮**（验证逻辑内置于 `vm.save()`） |
| `onNodeWithText("services:", substring)` | 新 profile 的 YAML 默认为**空**（`addProfile` 写入 `""`） |

## 核心设计决策

1. **消除 locale 依赖**：所有断言用资源字符串（`InstrumentationRegistry.context.getString(R.string.xxx)`），不再硬编码中文。默认字符串为英文，中文在 values-zh——硬编码断言只有 zh 设备能过。
2. **断言真实存在的元素**：以当前 UI 为准重写测试语义。
3. **保持文件路径与类名**：仍为 `androidTest/kotlin/cn/liukebin/GostX/` 下的 HomeScreenTest / ConfigScreenTest。

## 新测试规格

### HomeScreenTest（4 个测试）

- **showsStartButtonWhenStopped**：repo 有 profile + `VpnState(STOPPED)` → FAB `vpn_start_label` contentDescription 显示
- **showsStopButtonWhenConnected**：repo 有 profile + `VpnState(CONNECTED, addr)` → FAB `vpn_stop_label` contentDescription 显示
- **profileNameShownInList**：repo 有 profile → 列表显示 profile 名（`onNodeWithText(name)`）
- **emptyStateWhenNoProfiles**：repo 无 profile → `home_empty_profiles` 文案显示 + FAB 不存在（`assertDoesNotExist`）

### ConfigScreenTest（3 个测试）

- **yamlEditorShowsSavedContent**：`repo.saveConfig(id, "services:\n  - test")` → 编辑器显示该内容（`onNodeWithText("services:", substring=true)`）
- **saveButtonIsVisible**：`action_save` contentDescription 显示
- **renameButtonIsVisible**：`profile_rename` contentDescription 显示（替代已删除的"验证"按钮测试）

### 测试辅助

- `repo()`：`InstrumentationRegistry` 上下文 + 独立 prefs（`home_screen_test` / `config_screen_test`），每次 `clear().commit()`
- `repoWithProfile(name)`：返回 `Pair<ConfigRepository, String>`（repo + 新 profile 的 id），`addProfile(name)`

## 验证

- `cd android && ./gradlew connectedAndroidTest --console=plain`（设备 RFCX11HLKAZ 已连接）→ 全部通过
- 不依赖设备 locale（资源字符串断言）

## 明确不做

- 不新增 Compose UI 测试框架依赖（沿用 `createComposeRule`）
- 不改动 main 源码
- 不引入 mock 框架（ConfigScreenTest 现有写法已够用）

## 成功标准

- [ ] connectedAndroidTest 全绿（HomeScreenTest 4 个 + ConfigScreenTest 3 个）
- [ ] 断言全部基于资源字符串，无硬编码中文
