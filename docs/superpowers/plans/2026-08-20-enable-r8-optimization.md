# 开启 R8 应用优化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 release 构建开启 R8 代码+资源收缩与混淆，满足 Google Play 建议，并通过构建/测试/设备冒烟验证 VPN 功能不受影响。

**Architecture:** 在 `android/app/build.gradle.kts` 的 release buildType 开启 `isMinifyEnabled` 与 `isShrinkResources`（AGP 8.7.3 旧版 DSL），保持现有 `proguard-rules.pro` 的 `libgost.**` keep 规则（反射 + gomobile JNI 必需）。验证链：单元测试 → release 构建（临时 keystore 签名）→ 设备 instrumentation 测试 → 设备 VPN 冒烟测试 + R8 专项 logcat 检查。

**Tech Stack:** AGP 8.7.3、Gradle 8.9、Kotlin 1.9.24、Compose BOM 2024.10.00、gomobile AAR（libgost.aar）

## Global Constraints

- **AGP 8.7.3（< 9.3）→ 只能用旧版 DSL**：`isMinifyEnabled` / `isShrinkResources`，禁止使用 `optimization { enable = true }` 新 DSL
- **只改 release buildType**：debug、单元测试、instrumentation 测试构建不受影响
- **proguard-rules.pro 必须保留 `-keep class libgost.** { *; }`**：`Class.forName("libgost.Libgost")` 反射 + gomobile JNI 需要完整类名/方法名；libgost.aar 自带 consumer rules（`-keep go.**` / `-keep libgost.**`）由 AGP 自动合并
- **不新增除注释外的 keep 规则**：除非冒烟测试出现具体异常（如 SocketProtector 匿名实现问题），否则保持现状
- **不做 Startup Profiles / keep 收窄 / AGP 升级**（设计文档明确排除）
- **本仓库约定不代用户提交 git**：每个任务结束由用户自行提交（如需）
- **release 签名**：若环境已有 `KEYSTORE_PATH/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD` 用真实 keystore；否则用 Task 2 生成的临时 keystore（仅本地验证用，不上传 Play）
- 所有 Gradle 命令在 `android/` 目录下执行

---

### Task 1: 开启 R8 构建配置

**Files:**
- Modify: `android/app/build.gradle.kts`（release buildType 块，34-39 行）
- Modify: `android/app/proguard-rules.pro`

**Interfaces:**
- Consumes: 无
- Produces: release 构建开启代码收缩+资源收缩；proguard-rules.pro 保留规则 + 保护性注释

- [ ] **Step 1: 修改 build.gradle.kts 开启 R8**

把 release buildType 改为（改动 `isMinifyEnabled = false` → `true` 并新增 `isShrinkResources = true`）：

```kotlin
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
```

- [ ] **Step 2: 更新 proguard-rules.pro 保留规则说明**

把整个文件替换为：

```
# gomobile 生成的 Go bridge 类必须完整保留：
#   1. libgost.Libgost 通过 Class.forName + getMethod 反射调用（GostVpnService.kt 的 LibgostBridge）
#   2. go.** / libgost.** 经 JNI 被 Go 侧调用，类名与方法签名不可变
#   3. libgost.aar 自带 proguard.txt consumer rules（-keep go.** / -keep libgost.**），AGP 自动合并，
#      此文件规则为双重保险，勿删
-keep class libgost.** { *; }
```

- [ ] **Step 3: 验证配置可编译**

```bash
cd android && ./gradlew :app:compileReleaseKotlin
```

预期：`BUILD SUCCESSFUL`（此任务不需要签名，可验证配置语法与 R8 相关的资源收缩配置加载正常）。

---

### Task 2: 单元测试 + release 全量构建

**Files:**
- 无源码改动（纯验证任务）
- 产物：`android/app/build/outputs/apk/release/app-release.apk`、`android/app/build/outputs/bundle/release/app-release.aab`、`android/app/build/outputs/mapping/release/mapping.txt`

**Interfaces:**
- Consumes: Task 1 的构建配置
- Produces: 签名的 release APK（设备冒烟测试用）、mapping.txt（崩溃还原用）

- [ ] **Step 1: 确认签名环境，缺则生成临时 keystore**

```bash
# 检查现有环境
env | grep -E "KEYSTORE|KEY_ALIAS|KEY_PASSWORD" || echo "NO_SIGNING_ENV"

# 若为空，生成临时 keystore（密码统一 temp123456）：
if [ ! -f /tmp/gostx-test-keystore.jks ]; then
  keytool -genkeypair -v -keystore /tmp/gostx-test-keystore.jks \
    -alias gostx -keyalg RSA -keysize 2048 -validity 3650 \
    -storepass temp123456 -keypass temp123456 \
    -dname "CN=GostX Test, OU=Test, O=GostX, L=City, S=State, C=CN"
fi
export KEYSTORE_PATH=/tmp/gostx-test-keystore.jks
export KEYSTORE_PASSWORD=temp123456
export KEY_ALIAS=gostx
export KEY_PASSWORD=temp123456
```

- [ ] **Step 2: 运行单元测试**

```bash
cd android && ./gradlew test
```

预期：`BUILD SUCCESSFUL`，所有单元测试通过（含 `:app:testReleaseUnitTest` —— 验证 R8 开启不影响编译期单元测试）。

- [ ] **Step 3: 构建 release APK + AAB**

```bash
cd android && ./gradlew assembleRelease bundleRelease
```

预期：`BUILD SUCCESSFUL`，R8 正常执行（日志中出现 `minifyReleaseWithR8` 任务且无错误）。

- [ ] **Step 4: 检查 R8 产物**

```bash
ls -la app/build/outputs/apk/release/app-release.apk \
      app/build/outputs/bundle/release/app-release.aab \
      app/build/outputs/mapping/release/mapping.txt
# 记录 APK 大小（与开启前对比，预期明显变小）
ls -lh app/build/outputs/apk/release/app-release.apk
# 抽查 mapping 内容：libgost.Libgost 应保持原名（keep 生效）
grep -c "libgost.Libgost" app/build/outputs/mapping/release/mapping.txt || echo "NOT_FOUND"
```

预期：三个产物存在；mapping.txt 中存在 `libgost.Libgost`（keep 规则生效，反射类未被改名）。

---

### Task 3: 设备 instrumentation 测试

**Files:**
- 无源码改动（纯验证任务）

**Interfaces:**
- Consumes: Task 2 的构建产物（测试 APK 由 gradle 自动处理）
- Produces: 设备上 instrumentation 测试全绿

前置：USB 连接真机/模拟器，开启 USB 调试，`adb devices` 可见设备。

- [ ] **Step 1: 确认设备在线**

```bash
adb devices
```

预期：至少一行 `<serial>\tdevice`。

- [ ] **Step 2: 运行 instrumentation 测试**

```bash
cd android && ./gradlew connectedAndroidTest
```

预期：`BUILD SUCCESSFUL`，`HomeScreenTest`、`ConfigScreenTest` 全部通过（release 变体 APK + R8 混淆代码上运行）。

- [ ] **Step 3: 检查测试报告**

```bash
ls app/build/reports/androidTests/connected/
# 打开 index.html 确认 0 failures
```

---

### Task 4: 设备 VPN 冒烟测试 + R8 专项检查

**Files:**
- 无源码改动（人工冒烟验证）
- 用 Task 2 的 `app-release.apk`

**Interfaces:**
- Consumes: Task 2 的 release APK
- Produces: VPN 全流程可用性确认 + R8 无回归结论

- [ ] **Step 1: 安装 release APK 并启动**

```bash
cd android && adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n cn.liukebin.gostx/.MainActivity
```

预期：应用正常启动，无崩溃、无 ANR。

- [ ] **Step 2: 启动 VPN 全流程冒烟**

按以下顺序操作并确认每项：
1. 首页点启动 VPN → 系统 VPN 授权弹窗出现并授权 → 状态变为「Running」
2. `adb shell dumpsys connectivity | grep -i vpn` 或 logcat 确认 TUN 已建立（`startTun` 日志无 error）
3. 访问一个外网地址（浏览器/curl via proxy 均可）确认代理连通
4. 打开一个需要 DNS 解析的页面，确认 DNS 正常（虚拟 DNS 10.0.0.3:53 拦截生效）
5. 日志页滚动刷新正常（logbridge 工作正常）
6. 切换配置（Settings → 切换 profile 或编辑保存）再启动，正常
7. 杀掉 app 进程后重新启动 VPN，确认无「address already in use」类错误

- [ ] **Step 3: logcat R8 专项检查**

```bash
adb logcat -d | grep -iE "NoSuchMethodError|NoSuchMethodException|ClassNotFoundException|Resources\$NotFoundException|android.content.res.Resources" | grep -v "libgost not available" || echo "R8_CLEAN"
```

预期：`R8_CLEAN`（无 R8 相关的反射/资源异常）。

- [ ] **Step 4: 验证崩溃还原链路（mapping 可用性）**

```bash
# 确认 mapping 已就位（Task 2 已生成），记录路径供未来使用
ls -la app/build/outputs/mapping/release/mapping.txt
```

预期：mapping.txt 存在（若未来有 release 崩溃，可用 `retrace` 还原堆栈）。

---

## 成功标准（对照设计文档）

- [ ] Task 1-4 全部完成
- [ ] `./gradlew test` + `connectedAndroidTest` 全绿
- [ ] release APK 安装后 VPN 冒烟全通过，logcat 无 R8 相关异常
- [ ] mapping.txt 存在且含 `libgost.Libgost`

## 回滚方案

若冒烟测试出现 R8 相关崩溃且无法通过 keep 规则修复，回滚 = 把 `isMinifyEnabled` 改回 `false` 并删除 `isShrinkResources = true`（Task 1 的逆操作），重新构建验证。
