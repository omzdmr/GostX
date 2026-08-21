# 开启 R8 应用优化 — 设计文档

日期：2026-08-20
状态：已批准

## 背景

Google Play 建议参考 [Enable app optimization with R8](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization) 优化应用。该页面的核心建议是：为 release 构建开启 R8 应用优化（代码收缩、逻辑优化、混淆、资源收缩），以获得更快的启动时间、更低的内存占用、更小的包体。

## 现状

| 项 | 值 | 影响 |
|---|---|---|
| AGP / Gradle | 8.7.3 / 8.9 | 需用旧版 DSL（`isMinifyEnabled` / `isShrinkResources`） |
| release 构建 | `isMinifyEnabled = false` | R8 完全未启用 |
| proguard-rules.pro | `-keep class libgost.** { *; }` | 已有 Go AAR 保护规则 |
| libgost.aar | 自带 consumer rules（`-keep go.**` / `-keep libgost.**`） | AGP 自动合并，双保险 |
| 反射 | 仅 `Class.forName("libgost.Libgost")` + `getMethod`，全在 `libgost.**` 内 | 已被 keep 覆盖，安全 |
| 序列化库 | 无 Gson/Moshi/kotlinx.serialization | 反射风险极低 |
| Manifest 组件 | MainActivity / GostVpnService / GostTileService / BootReceiver | R8 自动保留 |
| R8 full mode | gradle.properties 无 `android.enableR8.fullMode=false` | 已默认全模式（AGP 8.x） |

## 目标

为 release 构建开启 R8 代码 + 资源收缩与混淆，满足 Google Play 建议，同时保证 VPN 功能不受影响。

## 改动

### 1. `android/app/build.gradle.kts`（核心改动）

release buildType 中新增两行：

```kotlin
release {
    isMinifyEnabled = true      // 代码收缩 + 混淆
    isShrinkResources = true    // 资源收缩
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")  // 已存在，保持
    signingConfig = signingConfigs.getByName("release")  // 已存在，保持
}
```

仅影响 release 构建；debug、单元测试、instrumentation 测试不受影响。

### 2. `android/app/proguard-rules.pro`

- 保留现有 `-keep class libgost.** { *; }`
- 新增注释说明保留原因（反射 + gomobile JNI 需要完整类名/方法名），防止未来被误删
- 不主动新增其他 keep 规则；若设备冒烟测试出现异常（如 SocketProtector 匿名实现问题），再按需补充

## 明确不做（本期）

- **Startup Profiles / Baseline Profiles**：收益明显但需设备采集，独立任务，第二期做
- **keep rules 收窄**：`libgost.**` 仅 3 个类，保留成本≈0，收窄无收益
- **升级 AGP 9.3 新 DSL**：不动构建版本

## 验证计划

1. **构建**：`make android-release` 成功产出 AAB + APK
2. **单元测试**：`./gradlew test` 全绿
3. **instrumentation 测试**：`./gradlew connectedAndroidTest`（HomeScreenTest、ConfigScreenTest）
4. **设备冒烟测试**（release APK）：
   - 启动 VPN，确认 TUN 建立、代理连通、DNS 解析
   - 日志滚动正常、配置切换/保存正常
   - 杀进程重连正常
   - logcat 无 `NoSuchMethodError` / `ClassNotFoundException` / 资源缺失
5. **mapping 检查**：确认 release 构建输出 mapping 文件（崩溃堆栈可还原）

## 成功标准

- [ ] release 构建成功
- [ ] 单元测试 + instrumentation 测试全绿
- [ ] 设备冒烟测试全通过，无 R8 相关崩溃
- [ ] mapping 文件存在
