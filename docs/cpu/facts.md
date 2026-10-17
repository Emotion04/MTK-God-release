# CPU 当前事实

设备观察适用于 [开发基线](../VERSIONS.md)，执行身份和日期在条目中说明。

<a id="cpu-001"></a>
## CPU-001 PowerHAL 协议与句柄

CPU 控制通过 `power_hal_mgr_service` acquire `0x16` / release `0x17`，
由 `CpuControl` 串行协调；释放失败时保留旧句柄。
协议、flags 和错误分支见 [protocol.md](protocol.md)，所有权见 [工程架构](../dev/architecture.md)。
依据：`CpuControl.kt`、`PerfLockController.kt`、`PowerHal.kt` 及协议页列出的一手实现。

<a id="cpu-002"></a>
## CPU-002 频率下限控制

单值及系统允许范围内的区间控制有设备验证，下限是已观察到可控的部分；
这不证明请求能越过固件当前上限。回归条件见 [known-good.md](known-good.md)。
依据：build 11 设备报告；本次未重跑 CPU 写入。

<a id="cpu-003"></a>
## CPU-003 频率上限由系统温控约束

温控配置和策略选择会改变超大核可达到的频率。软、硬 MAX 请求未证明能越过固件约束，
返回句柄不能据此显示为已解除限频。
关联：[THERM-001](../thermal/facts.md#therm-001-系统默认温控)、
[THERM-002](../thermal/facts.md#therm-002-手动选择策略)；
负载峰值的测量边界见 [DEV-007](../device/facts.md#dev-007-有效频率上限随温度漂移)。
依据：[2026-09-26 干净启动对照](../experiments/EXP-20260926-01-clean-boot-thermal-policy.md)
及 THERM-002 的后续测试。

<a id="cpu-004"></a>
## CPU-004 频率读回能力

2026-10-05 ADB shell 可读 `scaling_cur_freq`；三个 policy 的
`scaling_min_freq` / `scaling_max_freq` 为 `0660 system:system`，读回被拒。
shell 不在 system 组，DAC 已足以拒绝访问；相同 SELinux 标签不能排除其他策略限制。

采样峰值仅说明测试期间达到的频率，不等于真实上限。
`cpuinfo_max_freq` 是硬件报告范围，不能代替当前限制。
权限历史及开发影响见 [DEV-003](../device/facts.md#dev-003-cpu-频率节点可见性)。
依据：2026-10-05 本次 `ls -lZ`、`id`、`cat scaling_max_freq` 只读核验。

<a id="cpu-005"></a>
## CPU-005 兼容策略

簇布局和频率表由 `CpuScanner.kt` 运行时发现；
PowerHAL 私有协议和温控行为按 [兼容范围](../VERSIONS.md) 记录。
本机的 policy 编号和数值不是所有天玑设备的常量；尚无第二 ROM 的同条件对照。

<a id="cpu-006"></a>
## CPU-006 热插拔与硬上限在本机的可用性

| 资源 | 已有观察 |
|---|---|
| 每簇核数 `0x00800000` / `0x00804000` | 2026-10-05 旧短时采样未看到热插拔；未证明这两项可控制核数 |
| 逐核上下线 `0x00810000 + 0x100×(核+1)` | 仅实验能力，效果未验证 |
| 逐核暂停调度 `0x0080C000 + 0x100×(核+1)` | 仅实验能力，效果未验证 |
| 硬上限 `0x0040C000` | 参考后端依赖的 `/proc/ppm/policy/hard_userlimit_cpu_freq` 未检出；未验证固件是否另有后端 |

本次只读 `present` / `online` 均为 `0-7`，可列的 `/proc` 顶层没有 `ppm`。
这不能推出所有场景都不会热插拔，也不能单凭一个路径缺失把整个资源判为 no-op。
验证逐核行为可比较 `online`、`affected_cpus` 和可用调度状态；核隔离不一定等于离线。
依据：2026-10-05 ADB 观察；资源定义与适配证据见 [resources.md](resources.md)。

### 2026-10-14 复核：`/proc/ppm` 是 ENOENT，不是权限拒绝

`ls -l /proc/ppm/` 与 `cat /proc/ppm/policy/hard_userlimit_cpu_freq` 都是
`No such file or directory`。按 `Sysfs.NodeState` 的规矩，父目录 `/proc` 列得动，
所以这是**目录真的不存在**，不是 SELinux 拒掉搜索。

由此推着改了两处：

1. **实验室「硬上限(每簇)」的说明与事实页矛盾，已改。** 它原来写着
   "写在 /proc/ppm/policy/hard_userlimit_cpu_freq" 且"普通释放不会撤销它" ——
   前半句与本节冲突，后半句没有任何依据。实际行为是：CPU 页每一次区间请求都带着
   这个资源号，释放走同一条 0x17 oneway，用户实测释放有效。
2. **只读面板里四行 PPM 读数是死行，已删。** 每轮读四个不存在的路径，
   界面还显示一行「PPM 软上限」。

**仍未解决**：[resources.md](resources.md) 里 BSP 原文写着
"setClusterHardFreq() is a NO-OP if /proc/ppm/policy/hard_userlimit_cpu_freq does not exist"。
节点不存在，按文档这次写入应该是空操作；但 CPU 页的区间控制在用户手里是生效的。
两种可能：固件另有后端，或效果来自同一请求里的软上限那一对。
**分辨办法就是「逐 ID 测试」单独发 MAX_HL** —— 那是它存在的理由，别删。

### 2026-10-14 实测：四个资源单独发都生效，软硬齐发是冗余的

`/proc/ppm` 不存在，按 BSP 文档硬上限该是 no-op。用 `adb shell service call
power_hal_mgr_service 22` 逐个单独发（policy7，时长 8 秒，发完即释放）实测：

| 单独发哪一个 | 读回 `scaling_max_freq` / `scaling_min_freq` |
|---|---|
| 软 `MAX` `0x404200` = 1500000 | 上限 2100000 → **1500000** |
| 硬 `MAX_HL` `0x40C200` = 1500000 | 上限 → **1500000** |
| 软 `MIN` `0x4194816` = 2000000 | 下限 600000 → **2000000**，cur 跟着上去 |
| 硬 `MIN_HL` `0x408200` = 2000000 | 下限 → **2000000**，cur 跟着上去 |
| 释放（0x17，oneway） | 四项全部回到原值，无残留 |

**结论一：固件另有后端。** 节点不存在没有让这次写入变成空操作，BSP 文档那句
"NO-OP if … does not exist" 在本机不成立。

**结论二：软的那一对就够，硬的那一对是冗余的。** 同一个数值，软的能压硬的也能压，
单独发效果相同。`CpuControl` 现在每次区间请求软硬四项齐发，多两趟 binder、
多两个可能失败的释放点，换不来任何额外效果。

**软硬都冲不破系统包络。** 这与旧调查里"软 MIN=3300000 被常驻上限压在 2300"一致：
那一例的问题不是软或硬，是请求值超出了系统允许的包络（本机约 2000–2300）。
换硬的下限同样过不去 —— 所以这不是"留个硬上限设置给用户"能解决的场景。

**顺带纠正一处误读。** `resources.md` 里 BSP 原文 "Hard limits survive a soft release"
被读成了"任何释放都清不掉它"，实际说的是软硬分开请求时的行为。
**订正写在 [resources.md](resources.md) 那句原文旁边**，这里不重复。

依据：2026-10-14 ADB `service call` 实测，policy7（cluster index 2），
每次时长 8000 ms 且显式释放；8 秒后复查无残留。
`service call` **不要带 interface token** —— 带了 HAL 的 onTransact 会因为
parcel 长度对不上抛 "Allocation size … above allowed limit"。

### 2026-10-14 App 层复核：硬的那一对默认不发，是个开关

上面结论二落成了「设置 → 通用 → 附带硬上下限」，**默认关**。真机开起来之后
`PerfLock` 日志确认每簇发四个资源（软一对 + 硬一对），一次释放全清：

```
0x00400000=300000, 0x00404000=2000000,   ← policy0 软
0x00408000=300000, 0x0040C000=2000000,   ← policy0 硬
…policy4 / policy7 同样各四个…
```

**这个开关一开始是坏的**，而且坏得看不见：设置值写得进 DataStore，界面和
`CpuControl` 都读不回来（`launch` 里串了两个 `collect`，第二个是死代码），
编译单测全过。修法见 [architecture.md](../dev/architecture.md) 的
「一个 `launch` 里只放一个不会结束的 Flow」。换机型遇到"只认硬限"时再打开它。



