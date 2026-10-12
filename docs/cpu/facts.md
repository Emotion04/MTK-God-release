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
