# APK 构建

本次产物：`app/build/outputs/apk/debug/app-debug.apk`，约 16.65 MiB。

- 应用：KKCartographer Demo
- 包名：`com.cartographer.demo`
- 版本：1.0（versionCode 1）
- 系统：Android 8.0（API 26）及以上
- 架构：`arm64-v8a`
- 签名：本机 Android debug 签名，已验证 APK v2、v3 签名

当前目录原先只有 `main/` 和 `test/`，已添加 Gradle Wrapper、根工程及 `app` 模块配置。模块直接使用当前目录的源码、资源和 assets。

## 常规构建

需要 JDK 17、Android SDK Platform 34、Build Tools 34.0.0。在 `local.properties` 中设置 SDK 路径，然后运行：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

首次构建需要下载 Gradle 和 Maven 依赖。

## 本机离线构建

本次执行环境禁止 Gradle 守护进程创建本地套接字，因此使用离线脚本调用官方 Manifest Merger、AAPT2、Kotlin 编译器、D8、zipalign 和 apksigner 完成构建：

```bash
python3 tools/build_offline.py
```

脚本依赖本次准备的 `.gradle-user-home/caches/modules-2/files-2.1/` 缓存，以及本机 SDK 和 debug keystore。默认 SDK 为 `/home/cxz/Android/Sdk`，可通过 `ANDROID_HOME` 指定。这是针对当前机器的离线构建路径；迁移机器时建议使用常规 Gradle 构建。

离线构建使用缓存中可用的 AndroidX 运行库版本；完整依赖清单保存在 `app/build/offline/dependencies.txt`。本次 APK 中的 Kotlin 代码、资源、清单和 assets 均由当前项目重新构建。

## JNI 库来源

当前项目缺少 JNI 实现源码及动态库。本次从本机同包名工程的既有 APK 中提取以下预编译库，放入 `main/jniLibs/arm64-v8a/`：

- `libcartographer-jni.so`
- `libfloorplan-jni.so`
- `libopencv_java4.so`
- `libc++_shared.so`

来源 APK：`/home/cxz/Cartographer-Android0/app/build/outputs/apk/debug/app-debug.apk`。

已核对当前 Kotlin 的 JNI 声明与该工程 C++ 注册表一致，并检查所需动态库。库文件及来源 APK 的 SHA-256 记录在 `tools/native-libraries.json`。本次没有重新编译 C++ 算法库。

## 验证结果

- 当前项目 Kotlin 源码编译通过。
- 现有 `LidarScanFilterTest` 和 `OnlineMagneticNorthCalibratorTest` 共 11 项测试通过。
- APK ZIP 完整性、zipalign 对齐、v2/v3 签名校验通过。
- 已确认启动 Activity 为 `com.cartographer.demo.KaerActivity`，包含 4 个 ARM64 动态库及 18 个 assets 文件。
- APK 的 SHA-256 位于产物旁的 `app-debug.apk.sha256`。
- 尚未进行真机安装、启动或雷达硬件联调。

构建日志：`build-offline.log`。Kotlin 弃用接口、未使用变量及部分第三方库调试信息提示未阻止本次构建。

## 雷达 USB 连接修复

- 为 `2015:0008` 注册 CDC ACM 驱动，保留旧雷达的 CH340 驱动。
- 授权后只打开刚刚授权的设备，避免遍历时错误选中另一台串口设备。
- USB 权限请求限定到本应用，兼容 targetSdk 34 对 mutable PendingIntent 的要求。
- 连接失败或拒绝授权后可以再次点击“开始采集”重试。
- 切换协议时先关闭旧连接，再按所选协议重新连接；建图期间禁止切换。
- 串口读取启动后再下发雷达上电命令；需要雷达 IMU 时同时开启传感器上报。
- 设备断开或串口异常时释放连接，并在页面显示具体连接状态。

使用旧雷达时关闭“新雷达协议”；使用 KLD42 新雷达时打开该开关，然后点击“开始采集”并允许 USB 访问。新雷达默认不会给内部雷达模组上电，必须通过 USB 成功发送启动命令。

驱动及连接修复已通过编译和单元测试，但未进行真机验证。如果页面显示“手机未检测到 USB 设备”，应检查手机 OTG、数据线及供电；若显示其他 VID/PID，需要根据实际设备描述符确认型号和驱动。

新增的 `LidarUsbDriversTest` 验证新雷达 CDC 映射和旧雷达 CH340 映射。串口库的驱动表依赖 Android `Pair`，普通 JVM 使用 SDK 桩类时无法运行这两项测试，因此将它们放在 `androidTest/`，需通过 `./gradlew :app:connectedDebugAndroidTest` 在设备上运行；本次未运行。

## 当前转弯诊断和修复版

最新 APK 为 `app/build/outputs/apk/debug/PDA-turn-timing-fix-debug.apk`，包含 A 类跨包组帧/XOR 修复、USB 接收时间传递修复和 IMU 诊断信息。检查结论及限制见 `NEW_RADAR_DRIFT_REVIEW.md`。

离线 JVM 回归测试：

```bash
python3 tools/test_offline.py
```

当前共 21 项 JVM 测试通过。设备上的转弯效果仍需验证。

## 板载 IMU 启动流程修复

最新现场反馈确认板载 IMU 包为 0。当前最新安装包为 `app/build/outputs/apk/debug/PDA-imu-startup-fix-debug.apk`：雷达上电确认后开启板载 IMU，增加应答处理、超时重试及开始建图前的数据就绪检查。使用时先点击开始采集，待显示“已收到板载 IMU 数据”后再启动 SLAM；若失败，查看页面“板载启动”和“控制应答”。30 项 JVM 测试通过，真机效果待验证。

## 当前转弯数据流修复版

最新安装包：`app/build/outputs/apk/debug/PDA-turn-stream-fix-debug.apk`。保留已恢复的板载 IMU 启动流程及现有单位换算；新增独立 USB 输入队列与批量 IMU 接收时间估计，显示处理延迟和积压统计。41 项 JVM 测试通过，尚需真机转弯对照验证。没有 MCU 采样时间戳时，接收时间估计不能等同于硬件同步。

## 转弯现场数据采集版

最新 APK：`app/build/outputs/apk/debug/PDA-turn-capture-debug.apk`。新增“记录转弯诊断 / 停止记录并导出”，通过 Android 系统保存文件窗口导出 ZIP，供现场仍严重漂移时定位。它保持上一版 native 库、Lua 及现有传感器换算；不宣称漂移已解决。

45 项 JVM 测试、7 项 Python 记录格式测试通过。解析导出包：

```bash
python3 tools/analyze_turn_diagnostics.py PDA_turn_*.zip --stationary-seconds 5
```

只有确认记录开始后的前 5 秒保持静止时，才设置 `--stationary-seconds 5`。完整流程见 `NEW_RADAR_DRIFT_REVIEW.md`。

## 应用侧纯雷达转弯版

当前安装包：`app/build/outputs/apk/debug/PDA-lidar-only-turn-debug.apk`。新雷达默认开启“纯雷达建图（暂不融合IMU）”，加载无 IMU 的 Cartographer 配置，并扩大转弯角度搜索窗口。原融合模式仍可切回；旧雷达默认行为保留。

45 项 JVM 测试、10 项 Python 测试（含 3 项真实 Lua 配置测试）通过，APK 编译、签名、ZIP 及对齐检查通过。真机转弯质量尚待验证；操作步骤及限制见 `SOFTWARE_LIDAR_ONLY.md`。

## 移除转弯诊断界面版

用户已反馈纯雷达版墙体重影消失、回到起点地图可对齐。本次按要求移除“记录转弯诊断”按钮、操作说明及 Activity 内录制/导出交互，安装包为 `app/build/outputs/apk/debug/PDA-lidar-only-clean-debug.apk`。所有 SLAM 配置与 native 库均与上述现场验证版逐字节一致，纯雷达开关与默认值保留。

## 旋转界面流畅度优化版（2026-11-16）

安装包：`app/build/outputs/apk/debug/PDA-rotation-ui-smooth-debug.apk`。位姿/状态读取和在线地磁拟合移至后台，界面仅消费最新缓存；后台任务不积压旧请求，减少状态文字刷新及布局工作，补充页面销毁清理。所有 SLAM 配置及 native 库沿用现场验证版。50 项 JVM 测试、10 项 Python 测试通过；说明及现场对照步骤见 `ROTATION_UI_PERFORMANCE.md`。
