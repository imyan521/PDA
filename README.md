# PDA 雷达建图

基于 Android 和 Cartographer 2D 的手持雷达建图应用，支持旧雷达和新雷达 A 类协议，可选择纯雷达或雷达 + IMU 融合建图。

## 当前功能

- USB 串口连接、授权和雷达上电控制。
- 实时点云、地图显示、轨迹及地图测量。
- 新建地图、结束优化、地图保存和加载地图补采。
- 新雷达默认采用纯雷达建图，可切回 IMU 融合模式。
- PDA 地磁采集、在线校准及地图磁北方向处理。
- 后台读取显示位姿和计算地磁拟合，减少主线程等待。

现场反馈：纯雷达版的转弯墙体重影消失，回到起点地图可对齐。最近的旋转界面流畅度优化已完成编译与回归测试，实际效果仍需真机确认。转弯诊断录制入口已从界面移除。

## 构建

需要 JDK 17、Android SDK Platform 34、Build Tools 34.0.0。项目使用 Gradle 8.6、Android Gradle Plugin 8.3.0 和 Kotlin 1.9.22。

1. 用 Android Studio 打开仓库根目录，配置 Android SDK；命令行构建也可通过本机 `local.properties` 指定 `sdk.dir`。
2. 执行：

   ```bash
   ./gradlew :app:assembleDebug
   ./gradlew :app:testDebugUnitTest
   ```

3. APK 输出到 `app/build/outputs/apk/debug/`。

首次构建需要联网下载依赖。应用最低支持 Android 8.0（API 26），当前仅打包 ARM64（`arm64-v8a`）。本机离线构建及测试脚本的使用条件见 [BUILDING.md](BUILDING.md)。

`main/jniLibs/arm64-v8a/` 包含运行所需的预编译动态库，本项目没有配置这些 native 库的源码重建流程。来源与校验信息见 [BUILDING.md](BUILDING.md) 和 [tools/native-libraries.json](tools/native-libraries.json)。

## 目录

| 路径 | 内容 |
| --- | --- |
| `main/java/` | Android 应用、协议解析及建图接口代码 |
| `main/res/` | 界面和资源 |
| `main/assets/config/` | Cartographer 建图配置 |
| `main/jniLibs/` | 预编译 native 库及配套文件 |
| `app/build.gradle` | 应用模块配置，源码目录指向根目录 `main/` |
| `test/`、`androidTest/` | JVM 及 Android 设备测试 |
| `tools/` | 离线构建、测试及诊断数据分析工具 |

## 使用

连接雷达后，选择对应协议，点击“开始采集传感器”；点云正常后启动 SLAM。新雷达建议保持“纯雷达建图”开启。切换建图模式前结束当前建图。

地磁数据来自 PDA 自带磁力计，不直接参与 Cartographer 扫描匹配；纯雷达模式仍保留地磁功能。

## 相关说明

- [纯雷达与 IMU 融合建图对比](纯雷达与IMU融合建图对比说明.md)
- [纯雷达代码改动和使用说明](SOFTWARE_LIDAR_ONLY.md)
- [旋转界面流畅度优化](ROTATION_UI_PERFORMANCE.md)
- [构建和验证记录](BUILDING.md)
- [雷达问题排查记录](NEW_RADAR_DRIFT_REVIEW.md)

构建产物、缓存、签名文件、本机配置及现场原始数据由 `.gitignore` 排除，不随源码提交。新机器生成的 debug 签名可能与原设备安装版本不同；需要覆盖原 APK 时，应在本机配置相同签名文件，签名文件不放入仓库。
