# MTK God

一个 Android 应用，**无需 root** 即可设置 MediaTek SoC 上的 **CPU 与 GPU 频率限制**，通过 [Shizuku](https://shizuku.rikka.app/) 实现。它直接与 MediaTek 的 PowerHAL manager 通信，也就是厂商自身的功耗调优所走的同一个服务。

面向运行 MediaTek Dimensity SoC 的设备；簇布局、频率表和可用 governor 全部在运行时发现，而非预先假定。

## 文档与当前状态

开发文档从 [docs/README.md](docs/README.md) 按任务进入。设备行为与功能能力以对应主题的 `facts.md` 为准；带日期的实验只证明其记录条件下的结果，归档材料不构成当前待办。

## 文档

| | |
|---|---|
| [docs/README.md](docs/README.md) | 文档总入口与按任务索引 |
| [docs/cpu/facts.md](docs/cpu/facts.md) | CPU 当前能力、限制和兼容范围 |
| [docs/thermal/facts.md](docs/thermal/facts.md) | 温控当前行为和已知边界 |
| [docs/device/facts.md](docs/device/facts.md) | 开发设备、权限和可观测能力 |
| [docs/dev/architecture.md](docs/dev/architecture.md) | 分层、`CpuControl` 不变式、设备适配、测试 |
| [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) | Agent 文档写入、实验、版本和归档规范 |

**文档一律用中文撰写**；代码、命令、路径、标识符和 URL 保持原样不翻译。给 AI agent 看的仓库约定在 [AGENTS.md](AGENTS.md)。

## 构建

需要 JDK 17+ 和 Android SDK（`compileSdk 37`）。

```bash
cd mtk-optimizer
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk   —— 日常开发
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk —— 交付用
```

**交付给用户的是 release 包**：它开了混淆和资源压缩，签名从
`mtk-optimizer/signing/keystore.properties` 读；那个目录被 gitignore，
新克隆的仓库里没有，此时 `assembleRelease` 会因未签名而失败（这是有意的，
比静默退回 debug 签名好）。

**签名密钥不能换。** 换了签名的 APK 装不到已装的包上——用户得先卸载，
本地方案、设置和报告一起丢。密钥请离线备份，也不要提交进版本库。

## 设备上的要求

- Android 13+（`minSdk 33`）
- [Shizuku](https://shizuku.rikka.app/) 正在运行，且本应用已获授权
- 一颗 MediaTek SoC —— 在其他平台上，应用会检测到 PowerHAL 不存在并明确说明，而不是静默失败

## 帮助它支持你的设备

*设置 → 运行日志 → 复制报告* 会生成一份纯文本快照：SoC 与 build 标识符、按枚举顺序排列的 cpufreq policy、频率数量、governor 可写性、PowerHAL 可达性、完整的能力探测结果，以及运行日志。

这段文本通常足以在未持有设备的情况下推断出该设备的行为。目标是让大多数设备无需任何逐设备的适配工作——应用靠探测而非查询设备列表，报告是兜底手段。

## 许可证

尚未选定。
