# 温控当前事实

文件观察适用于 [开发基线](../VERSIONS.md)，运行时测试另列日期和条件。

<a id="therm-001"></a>
## THERM-001 系统默认温控

2026-09-26 清除本项目请求并干净重启的对照，默认行为与主 `thermal.conf` 相符；
皮温 41.6 °C 时观察到 policy7 上限 2100000。
主模包含 11 级 CPU 温度映射，例如 cluster2 的 37–40 °C 档为 2300000；
字段传感器为 `ap_ntc`，不能仅凭框架 SKIN 数值精确套表。

依据：[干净启动实验](../experiments/EXP-20260926-01-clean-boot-thermal-policy.md)
及 [配置基线](configs/README.md)。
2026-10-05 本次在线 SKIN 阈值与主模相符，但未重启或清理其他请求，不能替代干净启动条件。

<a id="therm-002"></a>
## THERM-002 手动选择策略

资源 `0x03000000` 可选择已有 `thermal_policy_NN.conf`。
旧对照中索引 01 可使 policy7 达到 3400000；文件内容见 [THERM-009](#therm-009-索引-01-不含-cpu-频率表已解密证实)。

2026-10-04 负载测试记录：

| 状态 | policy7 峰值 |
|---|---|
| 无本项目策略请求 | 2300000 |
| 索引 01 请求期间 | 3400000 |
| 索引 01 请求期间、系统 boost 关闭 | 3400000 |
| 释放后 | 2400000，SKIN 阈值回到主模组 |

该对照支持本次策略切换不依赖 boost；释放后的回读支持请求撤销，
不能据此确认所有服务和其他所有者均无残留句柄。

策略与 CPU 请求分别持有和释放。`duration=0` 依赖显式 release，
强制停止 App 不保证 UserService 中的请求消失。
依据：旧 [上限调查](../archive/cpu/frequency-ceiling-investigation-through-2026-09-26.md) 及上述后续记录；本次未重跑写入。

<a id="therm-003"></a>
## THERM-003 策略选路

固件配置有按应用和场景选择策略的映射；旧运行测试中游戏切换已复现，相机存在相反结果。
保留这一边界，不用一次静态配置核验推翻最近的有效场景测试。
按应用控制要容忍系统覆盖，并只恢复自己拥有的状态。

依据：[app-whitelist.md](configs/app-whitelist.md) 中的静态映射和历史测试。
2026-10-05 本次核对两张选路表哈希与本地副本一致，未进行场景切换。

<a id="therm-004"></a>
## THERM-004 配置文件与运行时读取

本机 21 份配置，其中 13 个可选策略文件名、11 份不同的策略内容。
逐行模 91 变换的定义、源路径和解码方法见 [configs/README.md](configs/README.md)；
2026-10-05 本次全部原件 SHA-256 与本地基线一致，21 份往返编码及明文比对通过。

读取时现场解析；格式变化或读取失败时明确报告，不能把旧固定说明当作新 ROM 的事实。

<a id="therm-005"></a>
## THERM-005 framework 与厂商策略的边界

历史框架状态覆盖没有证明厂商限频解除。
本次 `dumpsys thermalservice` 的 HAL cooling device 列表没有 CPU cpufreq 项；
这不能证明整个内核都没有 CPU 温控机制。
依据：2026-10-05 本次 HAL 快照及 [历史调查](../archive/thermal-investigation-through-2026-09-26.md)。

<a id="therm-006"></a>
## THERM-006 跨版本兼容

配置、格式和选路都限定于已验证 ROM；尚无第二 ROM 对照。
运行时读取和能力探测优先，更新步骤见 [VERSIONS.md](../VERSIONS.md)。

<a id="therm-007"></a>
## THERM-007 PEM 层设置键与 PowerHAL 策略索引是两套机制

`system pem_htp_level` / `pem_htp_thred` 是 settings 键，
`0x03000000` 是 PowerHAL 策略选择，不能把两者效果混用。
2026-10-02 读取值为 `0` 和 `40-52-58-1000-1000-1000`；
六段的确切语义未公开。前几段疑似温度阈值，不能照抄外部的另一组数值。
依据：该日期 `settings get system`；本次未重验。

<a id="therm-008"></a>
## THERM-008 目标温度不是“CPU 温度”

配置可直接确认 `steady_target_tj`、`min_target_tj`、`max_target_tj` 与 `sensor`：

| 策略 | sensor | steady_target_tj |
|---|---|---|
| 00、05、10、11 | `tz_game_shell` | 65 °C |
| 02、12 | `tz_shell` | 65 °C |
| 08、09、18 | `tz_shell` | 90 °C |
| 01、07、13 | `ap_ntc` | 91.5 °C |
| 03 | `ap_ntc` | 65 °C |

13 份策略的 min/max 为 55/95 °C。字段名支持“控制目标”的解释，
但控制律与各名称的物理位置未经确认；不同名字也可能映射、融合或别名引用同一来源。
展示目标时带上原始 sensor 名与范围，不能将其写成 CPU 降频触发线。
依据：2026-10-05 本次核对的明文；完整字段与推断边界见 [field-reference.md](configs/field-reference.md)。

<a id="therm-009"></a>
## THERM-009 索引 01 不含 CPU 频率表（已解密证实）

`thermal_policy_01.conf` 没有 `[mtk-cpu-table-mapping]`；
08/09 有该段但 `mode=disabled`。其他可选策略有 enabled 表，
主模也有 CPU 映射。

结合 THERM-002，缺少该映射是索引 01 高频表现的重要解释；
不能推出温度永远不会影响 CPU，或其他温控机制全被关闭。
依据：2026-10-05 本次基线解码核对；各策略表见 [配置索引](configs/README.md)。

<a id="therm-010"></a>
## THERM-010 其他温控机制

配置还含 CPU isolate、GPU 频率表、充电限流、LTF、
TPCB 目标以及 thermal HAL 阈值；不同策略的段和 mode 并不相同。
字段解释和明确未知项见 [field-reference.md](configs/field-reference.md)。

文件中的 enabled 表示配置值，不能代替逐项运行效果验证。
尤其“isolate”是否离线核心、LTF 的具体保护行为，不能单凭名称断言。
取消 CPU 映射不等于取消所有温控。
依据：2026-10-05 本次基线解码；没有新增逐项触发测试。

<a id="therm-011"></a>
## THERM-011 SKIN 阈值是粗指纹

`dumpsys thermalservice` 的 HAL SKIN 静态阈值可与配置比较：

| 阈值组（°C） | 基线文件 |
|---|---|
| 40/42/45/48/60/80 | 00、02、05、07、10、11、12、13、18 |
| 41/44/46/48/60/80 | 01、08、09 |
| 40/43/45/47/60/80 | 03 |
| 39/41/44/47/60/80 | 主模 `thermal.conf` |

**它不是一个固定值。** 阈值组由当前加载的策略决定，而策略会随场景切换，服务也可能动态改写。
所以这里只记"读到过哪些组"，**不把某一次读数当作"当前是哪一份"**。

| 读到的组（°C） | 对得上哪些配置 | 读取时的状态 |
|---|---|---|
| `40/42/45/48/60/80` | 00、02、05、07、10、11、12、13、18 | 2026-10-05，`IsStatusOverride=false` |
| `39/41/44/47/60/80` | 主模 `thermal.conf` | 同日另一次读取 |

两次都在 `IsStatusOverride=false` 下，却读到不同的组。**哪一次对应什么状态没有记录，
所以不能据此断言"当前加载的是主模"。** 要判断当前生效的是哪一份，读取时必须同时记录
当时有没有本项目的策略请求、前台应用和运行时长。

多份配置共享同一组阈值，相符或不符都不能单独唯一指认加载文件。
此命令通过 ADB shell 读取，不代表普通 App 无需权限就能访问同样信息。
依据：两次 `dumpsys` 与基线明文比较；差异原因待查。
