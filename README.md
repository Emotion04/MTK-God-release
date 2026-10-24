# MTK God

一个 Android 应用，**无需 root** 即可设置 MediaTek SoC 上的 **CPU 与 GPU 频率限制**，通过 [Shizuku](https://shizuku.rikka.app/) 实现。它直接与 MediaTek 的 PowerHAL manager 通信，也就是厂商自身的功耗调优所走的同一个服务。

面向运行 MediaTek Dimensity SoC 的设备；簇布局、频率表和可用 governor 全部在运行时发现，而非预先假定。

## 文档

文档随源码一起维护在私有仓库，公开快照只保留构建所需的源码与本节说明。
应用运行时探测设备能力，不依赖预置的设备表。

## 构建

需要 JDK 17+ 和 Android SDK（`compileSdk 37`）。

```bash
cd mtk-optimizer
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

`assembleRelease` 开启混淆与资源压缩，签名读取 `mtk-optimizer/signing/keystore.properties`；
该文件不入库，缺失时产出未签名包，不能覆盖安装官方发布版。

## 设备上的要求

- Android 13+（`minSdk 33`）
- [Shizuku](https://shizuku.rikka.app/) 正在运行，且本应用已获授权
- 一颗 MediaTek SoC —— 在其他平台上，应用会检测到 PowerHAL 不存在并明确说明，而不是静默失败

## 帮助它支持你的设备

*设置 → 运行日志 → 复制报告* 会生成一份纯文本快照：SoC 与 build 标识符、按枚举顺序排列的 cpufreq policy、频率数量、governor 可写性、PowerHAL 可达性、完整的能力探测结果，以及运行日志。

这段文本通常足以在未持有设备的情况下推断出该设备的行为。目标是让大多数设备无需任何逐设备的适配工作——应用靠探测而非查询设备列表，报告是兜底手段。

## 许可证

尚未选定。
