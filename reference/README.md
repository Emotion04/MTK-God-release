# reference/ — 第三方材料，不发布

这里要么是别人的文件，要么是从别人的二进制里解出来的东西。用途只有一个：
让 `docs/` 里记的结论能回头核对。构建和运行都不需要它。

**整个目录被 git 忽略，只有本文件和 [`LEDGER.md`](LEDGER.md) 受跟踪。** 素材本体不提交；
哪些来源分析过、功能落到本项目哪里记在台账里，因为它要能被 review。

## 三层

| 层 | 目录 | 规则 |
|---|---|---|
| 一手材料 | `sources/<YYYY-MM>-<批次>/` | 只增不改，原件不改名，进库即算 sha256 并登记 |
| 派生 | `derived/` | 可再生成；删之前把再生成命令写进台账 |
| 长期来源 | `rom/`、`research/` | 按主题保留；事实页按这两个路径取证，不动 |
| 工具链 | `tools/`、`libs/` | 解包与反编译工具、依赖 |

## 目录地图

| 路径 | 内容 |
|---|---|
| `sources/2026-09-ax-module/` | vivo AxManager 无 Root 模块 v1.3.0（shell + 5 个 jar + WebUI） |
| `sources/2026-09-startravel/` | startravel 1.3.0 与中间包；其 `1.3.0..apk` 是 SecShell 加固包 |
| `sources/2026-10-b1/` | 2026-10 第一批：Kbattery 1.2.3、Metric 1.5.3、MOs Premium v13、蓝河工具箱 6.12、游戏蓝盒 1.1.4 |
| `sources/2026-10-lhgjx/` | 来源待确认的 Flutter 应用（35 MB），未分析 |
| `sources/2026-10-b2/` | 2026-10-17 建的空批次，待入库 |
| `derived/decompiled/` | 4 个包的 CFR + jadx 双树，以及首批 apktool 解包产物 `java/`、`smali/`；`INDEX.md` 是命令/值/摘录索引 |
| `derived/dex-startravel/` | startravel 的 dex 转储与内存 dump |
| `derived/rom-analysis/` | 固件 framework / services 反编译 |
| `derived/.local-temp/` | `INDEX.md` 的生成脚本与中间 tsv。**保留原名**：脚本按 `.local-temp/` 前缀取文件，重跑时在 `reference/derived/` 下执行 |
| `derived/.local-baselines/` | 早期字符串基线 |
| `rom/PD2430D_A_16.1.12.0.W10/` | 固件配置与 build19 原件；`docs/device/pitfalls.md` 按此路径取证 |
| `research/` | 公开仓库取回的 MTK HAL 源码、scentbl / appcfg 配置 |
| `tools/` | apktool、jadx、cfr、dex2jar 与自用小脚本 |

## 新增来源

1. 放进 `sources/<YYYY-MM>-<批次或对象短名>/`，原件不改名。
2. 算 `sha256sum`。
3. 在 [台账](LEDGER.md) 加一行，状态 `raw`。

缺任一步视为未入库。分析到有文档 + 至少一个可复查位置后改 `analyzed`；
解不开的写 `blocked` 并写原因，不要留空。

## 使用注意

- **`derived/decompiled/<pkg>/` 的 Java 树不能单独信任。** 反编译器重建不了
  Kotlin 挂起函数状态机和内联 lambda，会留派发桩；Java 输出里找不到的调用点，
  先在 `_jadx` 树和首批 `smali/` 里查再下结论。这条记在 `docs/device/pitfalls.md`。
- `mtk-optimizer/` 不依赖本目录。`git status` 里除本文件外不应出现任何 `reference/` 内容。
