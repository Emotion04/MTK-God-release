# 参考来源台账

`reference/sources/` 里每个一手材料在这里占一行，未登记视为未入库。
深度摘录在 [docs/reference/apps/](../docs/reference/apps/README.md)，源码级行号在
`derived/decompiled/INDEX.md`。本页只记状态与落点，不复制结论，也不排移植优先级。

本文件与 [README.md](README.md) 是 `reference/` 里仅有的两个受 git 跟踪的文件：
素材本体不提交，但"哪些来源分析过、功能落到哪里"必须能随提交 review，否则会忘。

状态只有五个词：`raw` 仅入库 · `surveyed` 已清点未深读 · `analyzed` 有文档且至少一个可复查位置 ·
`blocked` 解不开；采纳状态另记 `adopted` / `trial` / `rejected` / `pending`。
**没核实就写 pending，不许猜。**

## 来源

| ID | 对象 | 版本 / sha256 前 8 位 | 本地路径 | 状态 | 可复查入口 | 结论落点 |
|---|---|---|---|---|---|---|
| src-kbattery-1.2.3 | Kbattery（蓝厂小护士） | 1.2.3 / `1d5a6703` | `sources/2026-10-b1/Kbattery1.2.3.apk` | analyzed | `derived/decompiled/kbattery/`（CFR 18338 + jadx 18405 文件）、INDEX §2.1 §3.1 | [kbattery.md](../docs/reference/apps/kbattery.md)（31 项） |
| src-metric-1.5.3 | Metric | 1.5.3 / `2cad5278` | `sources/2026-10-b1/Metric-1.5.3.apk` | analyzed，只到字节码级：**无反编译树** | [metric.md](../docs/reference/apps/metric.md) | [metric.md](../docs/reference/apps/metric.md)（40 项） |
| src-mos-13.2 | MOs Premium | v13.2 / 无 APK，脚本文本 | `sources/2026-10-b1/MOsPremiumV13/` | analyzed | 目录内 `action.sh`、`webroot/` 即源码 | [mospremium-v13.md](../docs/reference/apps/mospremium-v13.md)（31 项） |
| src-lanhe-6.12 | 蓝河工具箱 | 6.12 / `4400b6c2` | `sources/2026-10-b1/蓝河工具箱6.12.apk` | analyzed | `derived/decompiled/lanhe/`、INDEX §2.1 §3.2 | [lanhe-toolbox-6.12.md](../docs/reference/apps/lanhe-toolbox-6.12.md)（47 项） |
| src-youxilanhe-1.1.4 | 游戏蓝盒（剪锅准星） | 1.1.4 / `038993f1` | `sources/2026-10-b1/游戏蓝盒1.1.4.apk` | analyzed | `derived/decompiled/youxilanhe/`、INDEX §2.1 §3.3 | [youxilanhe-fps-module.md](../docs/tools/youxilanhe-fps-module.md) |
| src-startravel-1.3.0 | startravel | 1.3.0 / `27f7a71e` | `sources/2026-09-startravel/1.3.0..apk` | **blocked**：SecShell 加固，dex 只有 11 个 class_defs | INDEX §1.1 §2.2 的字节偏移 | 无源码，只有 payload 偏移 |
| src-startravel-temp | 同批 `temp.apk`，首批 apktool 解包对象 | 未知 / 未记 | `sources/2026-09-startravel/temp.apk` | surveyed：有平铺反编译树，未成文 | `derived/decompiled/{java,smali}/`（2026-09-08 首批） | 无独立文档 |
| src-ax-module-1.3.0 | vivo AxManager 无 Root 模块 | v1.3.0 / 目录 27 文件 | `sources/2026-09-ax-module/` | analyzed | shell 与 WebUI 源码、5 个 jar 的 dex 反汇编 | [vivo-ax-module.md](../docs/reference/vivo-ax-module.md) |
| src-rom-pd2430d | 固件 PD2430D_A_16.1.12.0.W10 | build19 / 348 文件 | `rom/PD2430D_A_16.1.12.0.W10/` | analyzed | 配置原件 + `derived/rom-analysis/` | [ax-rom-trace](../docs/reference/ax-rom-trace-2026-09-19.md)、[归档](../docs/archive/cpu/rom/implementation-2026-09-19.md) |
| src-research-mtk | 公开仓库取回的 HAL 源码与配置 | 无固定版本，含 `perfservice.cpp` | `research/` | surveyed：按需引用，未逐份核 | 见 [归档](../docs/archive/cpu/rom/configs-2026-09-18.md) | 同左 |
| src-lhgjx-unknown | Flutter 应用，名称待确认 | 未知 / `22b22eba` | `sources/2026-10-lhgjx/lhgjx.apk` | raw：35 MB、`flutter_assets` + `gdt_plugin`，与 6.12（Kotlin/Compose，4.7 MB）不同源 | 未解包分析 | 无 |
| src-mtk-god-apk-unknown | `app-debug-mtk-god.apk` 归属待确认 | 未知 / `7e4617b2` | `sources/2026-09-startravel/app-debug-mtk-god.apk` | raw | 未分析 | 无 |
| src-b2-empty | 2026-10-17 建的空批次 | — | `sources/2026-10-b2/` | raw（空） | — | 待入库 |

## 功能采纳

只登记**已经落地或不采用**的，未评估的写 pending 并注明。

| 功能 | 来源实现位置 | 本项目落点 | 状态 |
|---|---|---|---|
| Settings 统一写入与回读 | 多源取舍，见 [reference-teardown.md](../docs/reference/apps/reference-teardown.md) | `data/settings/SettingChannel.kt` | adopted |
| 请求所有权 + 串行 apply/release | 同上 | `data/cpu/CpuControl.kt` | adopted |
| 用操作前原值恢复，不写推定默认值 | 同上 | `data/settings/` 的快照与恢复路径 | adopted |
| `cmd shortcut reset-throttling`、假报温度 | `derived/decompiled/INDEX.md` §2.1 | 无 | rejected：[频率上限调查](../docs/archive/cpu/frequency-ceiling-investigation-through-2026-09-26.md) 判为未验证 + 高风险 |
| FPS 浮层观测（剪锅准星） | [youxilanhe-fps-module.md](../docs/tools/youxilanhe-fps-module.md) | 无 | pending：未评估 |
| vivo binder 控制面（亮度等） | [vivo-ax-module.md](../docs/reference/vivo-ax-module.md) | [vivo-smart-platform.md](../docs/tools/vivo-smart-platform.md) 记为候选 | pending：未评估 |

## 维护

- 新增来源：放 `sources/<YYYY-MM>-<批次>/` → 算 sha256 → 本页加一行 `raw`。
- 分析完成：同次补深度摘录（新对象进 [apps/](../docs/reference/apps/README.md)，归档分析进
  [archive/](../docs/archive/README.md)），再把状态改 `analyzed`。
- 功能合并进主项目：同次在本页 §2 加一行，落点写代码路径；契约写进
  [工程架构](../docs/dev/architecture.md) 或主题事实页，本页不复制。
- 删除 `derived/` 下任何产物前，先把再生成命令写进对应行的备注。
