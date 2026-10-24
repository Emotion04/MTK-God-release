# 参考来源台账

`../MTK-reference/sources/` 里每个一手材料在这里占一行，未登记视为未入库。
深度摘录在 [apps/](apps/README.md)，源码级行号在
`derived/decompiled/INDEX.md`。本页只记状态与落点，不复制结论，也不排移植优先级。

本文件与 [README.md](README.md) 是 `reference/` 里仅有的两个受 git 跟踪的文件：
素材本体不提交，但"哪些来源分析过、功能落到哪里"必须能随提交 review，否则会忘。

来源状态只有四个词：`raw` 仅入库 · `surveyed` 已清点未深读 · `analyzed` 有文档且至少一个可复查位置 ·
`blocked` 解不开；采纳状态另记 `adopted` / `trial` / `rejected` / `pending`。
**没核实就写 pending，不许猜。**

## 来源

| ID | 对象 | 版本 / sha256 前 8 位 | 本地路径 | 状态 | 可复查入口 | 结论落点 |
|---|---|---|---|---|---|---|
| src-kbattery-1.2.3 | Kbattery（蓝厂小护士） | 1.2.3 / `1d5a6703` | `sources/2026-10-b1/Kbattery1.2.3.apk` | analyzed | `derived/decompiled/kbattery/`（CFR 18338 + jadx 18405 文件）、INDEX §2.1 §3.1 | [kbattery.md](apps/kbattery.md)（31 项） |
| src-metric-1.5.3 | Metric | 1.5.3 / `2cad5278` | `sources/2026-10-b1/Metric-1.5.3.apk` | analyzed（2026-10-18 补建双树） | `derived/decompiled/metric/`（CFR 8419 + jadx 7927 文件）、INDEX §7 | [metric.md](apps/metric.md)（40 项） |
| src-mos-13.2 | MOs Premium | v13.2 / 无 APK，脚本文本 | `sources/2026-10-b1/MOsPremiumV13/` | analyzed | 目录内 `action.sh`、`webroot/` 即源码 | [mospremium-v13.md](apps/mospremium-v13.md)（31 项） |
| src-lanhe-6.12 | 蓝河工具箱 | 6.12 / `4400b6c2` | `sources/2026-10-b1/蓝河工具箱6.12.apk` | analyzed | `derived/decompiled/lanhe/`、INDEX §2.1 §3.2 | [lanhe-toolbox-6.12.md](apps/lanhe-toolbox-6.12.md)（47 项） |
| src-youxilanhe-1.1.4 | 游戏蓝盒（剪锅准星） | 1.1.4 / `038993f1` | `sources/2026-10-b1/游戏蓝盒1.1.4.apk` | analyzed | `derived/decompiled/youxilanhe/`、INDEX §2.1 §3.3 | [youxilanhe-fps-module.md](apps/youxilanhe-fps-module.md) |
| src-startravel-1.3.0 | startravel | 1.3.0 / `27f7a71e` | `sources/2026-09-startravel/1.3.0..apk` | **blocked**：SecShell 加固，dex 只有 11 个 class_defs | INDEX §1.1 §2.2 的字节偏移 | 无源码，只有 payload 偏移 |
| src-startravel-temp | 同批 `temp.apk`，首批 apktool 解包对象 | 未知 / `fd0e7e2a` | `sources/2026-09-startravel/temp.apk` | surveyed：有平铺反编译树，未成文 | `derived/decompiled/{java,smali}/`（2026-09-08 首批） | 无独立文档 |
| src-ax-module-1.3.0 | vivo AxManager 无 Root 模块 | v1.3.0 / 目录 27 文件 | `sources/2026-09-ax-module/` | analyzed | shell 与 WebUI 源码、5 个 jar 的 dex 反汇编 | [vivo-ax-module.md](vivo-ax-module.md) |
| src-rom-pd2430d | 固件 PD2430D_A_16.1.12.0.W10 | build19 / 348 文件 | `rom/PD2430D_A_16.1.12.0.W10/` | analyzed | 配置原件 + `derived/rom-analysis/` | [ax-rom-trace](ax-rom-trace-2026-09-19.md)、[归档](../archive/cpu/rom/implementation-2026-09-19.md) |
| src-research-mtk | 公开仓库取回的 HAL 源码与配置 | 无固定版本，含 `perfservice.cpp` | `research/` | surveyed：按需引用，未逐份核 | 见 [归档](../archive/cpu/rom/configs-2026-09-18.md) | 同左 |
| src-lhgjx-jumang | 蓝河工具箱（**同名不同源**） | 未知 / `22b22eba` | `sources/2026-10-lhgjx/lhgjx.apk` | raw：包名 `com.jumang.lhgjx`，Flutter（`flutter_assets` + `gdt_plugin`，compileSdk 36）。与 src-lanhe-6.12 的 `com.bluestudiocjd.app`（Kotlin/Compose，4.7 MB）**包名不同，因此不可能是 6.12 的升级包**；是否换了包名的重写版待定 | 未解包分析 | 无 |
| src-xplan-1.4 | XPlan（`com.itos.xplan`） | 1.4 / `833c6399` | `sources/2026-10-b2/XPlan_1.4(29).apk` | raw | 未解包 | 无 |
| src-shellbox-4.6.4 | 幻·实验室（`com.itos.shellbox`） | 4.6.4 / `70b32e64` | `sources/2026-10-b2/幻·实验室_4.6.4.apk` | raw | 未解包 | 无 |
| src-sysopt-2.2 | 系统优化（`com.itos.sysopt`） | 2.2 / `d79697b3` | `sources/2026-10-b2/系统优化_2.2.apk` | raw：带 `libjiagu_a64.so`（360 加固） | 未解包 | 无 |
| src-vivotools-1.8 | 蓝厂工具盒（`com.itos.vivotools`） | 1.8 / `805e7060` | `sources/2026-10-b2/蓝厂工具盒v1.8.apk` | raw：`assets/` 内嵌子 APK（`12.apk`、`16.apk`、`17.apk`） | 未解包 | 无 |
| src-stealth-1.3 | 隐秘开关（`com.itos.stealth`） | 1.3 / `bcf90d03` | `sources/2026-10-b2/隐秘开关_1.3.apk` | raw | 未解包 | 无 |
| src-batteryrecorder-2.1.5 | batteryrecorder（`yangfentuozi.batteryrecorder`） | 2.1.5-prerelease1 / `b788cf6f` | `sources/2026-10-b2/batteryrecorder-v2.1.5-prerelease1.apk.1` | raw：文件名带 `.1` 后缀（浏览器重复下载），内容仍是 APK | 未解包 | 无 |

## 功能采纳

只登记**已经落地或不采用**的，未评估的写 pending 并注明。

| 功能 | 来源实现位置 | 本项目落点 | 状态 |
|---|---|---|---|
| Settings 统一写入与回读 | 多源取舍，见 [reference-teardown.md](apps/reference-teardown.md) | `data/settings/SettingChannel.kt` | adopted |
| 请求所有权 + 串行 apply/release | 同上 | `data/cpu/CpuControl.kt` | adopted |
| 用操作前原值恢复，不写推定默认值 | 同上 | `data/settings/` 的快照与恢复路径 | adopted |
| `cmd shortcut reset-throttling`、假报温度 | `derived/decompiled/INDEX.md` §2.1 | 无 | rejected：[频率上限调查](../archive/cpu/frequency-ceiling-investigation-through-2026-09-26.md) 判为未验证 + 高风险 |
| FPS 浮层观测（剪锅准星） | [youxilanhe-fps-module.md](apps/youxilanhe-fps-module.md) | 无 | pending：未评估 |
| vivo binder 控制面（亮度等） | [vivo-ax-module.md](vivo-ax-module.md) | [vivo-smart-platform.md](../tools/vivo-smart-platform.md) 记为候选 | pending：未评估 |

## 全量 sha256

表格里的 8 位只够认出是哪个文件，不够校验手上这份对不对（换手、重下、被替换都看不出来）。
这里存全量；`../MTK-reference/tools/check_reference.py` 会把这一节和上表的 8 位一起比对。

| 路径 | sha256 |
|---|---|
| `sources/2026-10-b1/Kbattery1.2.3.apk` | `1d5a6703732d907516859ba4c384276e711635ac3293ac7a3aecf58d94badffe` |
| `sources/2026-10-b1/Metric-1.5.3.apk` | `2cad5278ada33197d864146f193d792a31ee1563643dd9c506008c0d2bd334dc` |
| `sources/2026-10-b1/蓝河工具箱6.12.apk` | `4400b6c26dca03d27212bf700b98a4c1d1e9acbe2e031884fc67ff424dd4bc7e` |
| `sources/2026-10-b1/游戏蓝盒1.1.4.apk` | `038993f1399a16acf8eb0380e8dada29914086cc5b402788d51589f2d3358dea` |
| `sources/2026-09-startravel/1.3.0..apk` | `27f7a71e74882fdea53e407568c614ff764070c8c7d4c1c4a82817e7ec4799e4` |
| `sources/2026-09-startravel/temp.apk` | `fd0e7e2a033c7317e2655b97c1d4c041f9d47af9dadae656418c325c374abc75` |
| `sources/2026-10-lhgjx/lhgjx.apk` | `22b22eba760c3232c97e77009726501d0c7092370e1b06b2aaa867aa1a07df7d` |
| `sources/2026-10-b2/XPlan_1.4(29).apk` | `833c63997d95dac3a6d29e946b37e96f124da0ce732cde48feb3952b90da5b01` |
| `sources/2026-10-b2/幻·实验室_4.6.4.apk` | `70b32e642bd56c7aeb5667f37fde8d8bc64e4a09356046ed56e9923e6274ba23` |
| `sources/2026-10-b2/系统优化_2.2.apk` | `d79697b332a67123e4a6595156b7f7eda9002c49edff1edda329ece08f0abdb8` |
| `sources/2026-10-b2/蓝厂工具盒v1.8.apk` | `805e7060d42b8608dfce0df59e161e8e8483a65ae4c7fcd67195435539a8036f` |
| `sources/2026-10-b2/隐秘开关_1.3.apk` | `bcf90d03ec29d917f8a08c77009291ed9442eff39e28f3c4912560a5e45ed3ac` |
| `sources/2026-10-b2/batteryrecorder-v2.1.5-prerelease1.apk.1` | `b788cf6fa630e0be4437d64555f6ed828d0b222ef7609db1ff678a31d436d722` |

## 维护

- 新增来源：放 `sources/<YYYY-MM>-<批次>/` → 算 sha256 → 本页加一行 `raw`。
- 分析完成：同次补深度摘录（新对象进 [apps/](apps/README.md)，归档分析进
  [archive/](../archive/README.md)），再把状态改 `analyzed`。
- 功能合并进主项目：同次在本页 §2 加一行，落点写代码路径；契约写进
  [工程架构](../dev/architecture.md) 或主题事实页，本页不复制。
- 删除 `derived/` 下任何产物前，先把再生成命令写进对应行的备注。
