# gomobile 生成的 Go bridge 类必须完整保留：
#   1. libgost.Libgost 通过 Class.forName + getMethod 反射调用（GostVpnService.kt 的 LibgostBridge）
#   2. go.** / libgost.** 经 JNI 被 Go 侧调用，类名与方法签名不可变
#   3. libgost.aar 自带 proguard.txt consumer rules（-keep go.** / -keep libgost.**），AGP 自动合并，
#      此文件规则为双重保险，勿删
-keep class libgost.** { *; }
