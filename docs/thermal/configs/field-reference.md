# `thermal/*.conf` 字段速查

覆盖本机忽略目录 `.local-baselines/thermal-crack/plain/` 下 21 份明文里出现的字段，
共 **10 个段、45 个 (段, 字段) 组合**。一行一个字段。

**约定**

| 标记 | 意思 |
|---|---|
| 【注释】 | 厂商注释或段名的说明；不等于已经验证运行行为 |
| 【推断】 | 从文件名 / 上下文 / 数值分布推出来的，未在真机或源码验证 |
| 【未知】 | 尚无足够证据确定含义 |

**单位约定**（21 份里高度一致）：温度一律是**毫摄氏度（m°C）**，即 `95000` = 95.0 °C；
CPU 频率为 **kHz**，即 `2100000` = 2.1 GHz；GPU 单位仍属推断；`current` 推断为 **mA**（未验证）。
`permanent` / `mode` / `type` / `operation` / `atc_mode` / `HW_protection` 是字符串枚举，无单位。

**这份表的统计口径**：列 2「段」按**段名**计，同名段在文件里出现多次算一处；
列 3「出现次数」= 含该字段的**文件数**（不是段出现次数）。

---

## 1. 主表

| 字段 | 出现在哪些段 | 出现文件数 | 单位 | 含义 |
|---|---|---:|---|---|
| `permanent` | `[policy_type]` | 21 / 21 | 枚举 Yes/No | 【注释】"If permanent= Yes, this policy is still valid after reboot" —— 重启后是否仍生效。值 `Yes` 仅 5 份：`thermal_policy_07`、`13`、`thermal.conf`、`thermal_mtbf.conf`、`disable_thermal.conf` |
| `thermal_zone` | `[LTF]` | 16 / 21 | 字符串（thermal zone 名） | 【推断】LTF 段绑定的 thermal zone。21 份里**恒为 `soc_max`**，无第二种取值，无法从变化推断语义 |
| `mode` | `[LTF]` | 16 / 21 | 枚举 enabled/disabled | 【推断】LTF 段的开关。16 处**恒为 `enabled`**。缺该段的 5 份是 `disable_thermal` / `_temp` / `disable_throttling` / `fix_ttj_85` / `fix_ttj_95`（它们用 `[LTF-disable-throttling]` 代替） |
| `sensor` | `[vtskin-param]` | 18 / 21 | 字符串（传感器名） | 【推断】本段要配置的虚拟皮温传感器编号。取值 `vtskin1`…`vtskin6`、`vtskin-max` |
| `mode` | `[vtskin-param]` | 18 / 21 | 枚举 disabled | 【推断】该段的启用字段。89 个显式 mode 均为 `disabled`；有些 operation 段不写 mode，不能由此说所有虚拟传感器都已禁用 |
| `operation` | `[vtskin-param]` | 5 / 21 | 枚举 `coefficient` / `maximum` | 【推断】该虚拟传感器如何由 `ref_sensor` 算出。`coefficient` 配 `ref_coef`（线性加权），`maximum` 配 4 个 vtskin 取最大 |
| `ref_sensor` | `[vtskin-param]` | 5 / 21 | 传感器名列表 | 【推断】`operation` 的输入源。两种形态：6 个物理 NTC（`ap_ntc, nrpa_ntc, wifi-ntc, battery, ltepa_ntc, quiet_ntc`）或 4 个 vtskin |
| `ref_coef` | `[vtskin-param]` | 2 / 21 | 无单位整数 | 【推断】`operation=coefficient` 时各源传感器的权重。仅 `thermal_policy_02` / `12` 有，量级 1e6~3e8，正负混合。**缩放因子未知**（是否需除以 2^N 推不出来） |
| `sensor` | `[mtk-skin-control]` | 21 / 21 | 字符串（传感器名） | 【推断】该皮温控制环读的温度源。取值 `ap_ntc`(12) / `tz_shell`(5) / `tz_game_shell`(4)。`tz_*` 与 `ap_ntc` 的区别含义未知 |
| `max_target_tj` | `[mtk-skin-control]` | 21 / 21 | m°C | 【推断】目标结温上限。`95000` 17 份、`300000` 3 份（`disable_thermal` / `_temp` / `disable_throttling`，配置目标很高，实际效果未验证）、`85000` 1 份（`fix_ttj_85`）。其中 6 份令 `min = max`（见下一行） |
| `min_target_tj` | `[mtk-skin-control]` | 21 / 21 | m°C | 【推断】目标结温下限。`55000` 15 份。**6 份令 `min = max`**：`disable_thermal` / `_temp` / `disable_throttling`（都 300000）、`disable_skin_control` 与 `fix_ttj_95`（都 95000）、`fix_ttj_85`（85000）。二者相等时的确切效果【未知】 |
| `trip_tpcb` | `[mtk-skin-control]` | 21 / 21 | m°C | 【推断】进入 tpcb 控制的温度（trip point）。取值 `45000`(7) / `53000`(5) / `51000`(2) / `50000`(2) / `46500`(2) / `47000`(1) / `48000`(1) / `77000`(1，`disable_skin_control`) |
| `target_tpcb` | `[mtk-skin-control]` | 21 / 21 | m°C | 【推断】tpcb 控制的目标温度。**21 / 21 恒比同行 `trip_tpcb` 高**，但差额不唯一：18 份高 `3000`，3 份高 `1000`（`thermal_policy_08` / `09` / `18`，也正好是带 `kp_ratio` 的三份） |
| `polling_time` | `[mtk-skin-control]` | 21 / 21 | **未知** | 【推断】控制环轮询周期。取值只有 1 / 5 / 10 三种。**单位未知** |
| `steady_target_tj` | `[mtk-skin-control]` | 21 / 21 | m°C | 【推断】稳态目标结温。与 `sensor` 强相关但不绝对：`ap_ntc` 11 份配 `91500`、1 份（`thermal_policy_03`）配 `65000`；`tz_game_shell` 4 份全 `65000`；`tz_shell` 2 份 `65000` + 3 份（`08` / `09` / `18`）`90000`。`sensor` 与 `steady_target_tj` 是否真有因果关系【未知】 |
| `atc_max_target_tj` | `[mtk-skin-control]` | 2 / 21 | m°C | 【推断】ATC 模式下的最大目标结温。仅 `disable_skin_control`、`thermal_mtbf`，值均 `105000`。ATC 是什么的缩写【未知】 |
| `atc_mode` | `[mtk-skin-control]` | 7 / 21 | 枚举 enable/disable | 【推断】ATC 开关。`enable` 只在 `disable_skin_control` 与 `thermal_mtbf`，其余 5 份 `disable` |
| `debug_mode` | `[mtk-skin-control]` | 5 / 21 | 枚举 enable | 【未知】5 处全在 `disable_*` / `fix_ttj_*`，值全 `enable`。与文件名"禁用"并列出现，语义不明 |
| `kp_ratio` | `[mtk-skin-control]` | 3 / 21 | 无单位 | 【未知】仅 `thermal_policy_08` / `09` / `18`，值均 `10`。`kp` 常指比例控制项，但这是名字猜想，非证据 |
| `mode` | `[charger-cooler]` | 16 / 21 | 枚举 enabled/disabled | 【推断】充电限流开关。15 处 `enabled`，仅 `disable_skin_control` 为 `disabled` |
| `sensor` | `[charger-cooler]` | 15 / 21 | 字符串（传感器名） | 【推断】充电限流读的温度源。`ap_ntc`(9) / `tz_game_shell`(4) / `tz_shell`(2)。`disable_skin_control` 只有 `mode=disabled`、不带 `sensor` |
| `threshold` | `[charger-cooler]` | 15 / 21 | m°C | 【推断】逐档限流的触发温度。数组长度 = 档数，1~4 不等 |
| `hysteresis` | `[charger-cooler]` | 15 / 21 | m°C | 【推断】每档回滞。**15 处每一个回滞值都是 `2000`**，无第二种数值；数组长度随档数变（4 档 10 份、1 档 5 份） |
| `current` | `[charger-cooler]` | 15 / 21 | **mA（推断）** | 【推断】每档允许的充电电流。取值 `1800 / 1000 / 500 / 0`，另有单档 `[0]`（"该温度点直接断充"）。单位是 mA 属推断，未验证 |
| `type` | `[thermalhal-threshold]` | 21 / 21 | 枚举 | 【推断】上报哪类阈值。**21 / 21 恒为 `SKIN`**。其他类型阈值不在这 21 份里，来源尚未确认 |
| `sensor` | `[thermalhal-threshold]` | 21 / 21 | 字符串（传感器名） | 【推断】阈值绑定的传感器。**21 / 21 恒为 `ap_ntc`** |
| `level` | `[thermalhal-threshold]` | 21 / 21 | 枚举列表 | 【推断】上报的温控等级名。6 级 `light, moderate, severe, critical, emergency, shutdown`（14 份）或 4 级 `severe, critical, emergency, shutdown`（7 份，全是 `disable_*` / `fix_ttj_*` / `thermal_mtbf`） |
| `threshold` | `[thermalhal-threshold]` | 21 / 21 | m°C | 【推断】与 `level` 一一对应的皮温阈值。ADB shell 可用 `dumpsys thermalservice` 读 HAL 的 SKIN 阈值进行比较；部分文件的阈值组相同，不能单独定位当前文件 |
| `mode` | `[mtk-cpu-table-mapping]` | 13 / 21 | 枚举 enabled/disabled | 【推断】本簇频率表是否生效。`enabled` 33 个簇段、`disabled` 6 个（`thermal_policy_08` / `09` 的三簇全 `disabled`） |
| `sensor` | `[mtk-cpu-table-mapping]` | 13 / 21 | 字符串（传感器名） | 【推断】频率表读的温度源。`tz_shell`(15 个簇段) / `ap_ntc`(12) / `tz_game_shell`(12) |
| `cluster` | `[mtk-cpu-table-mapping]` | 13 / 21 | 整数 0/1/2 | 【推断】CPU 簇编号。13 份每份都是 0、1、2 三段。**cluster 2 = `policy7`（大核簇）、cluster 1 = `policy4`、cluster 0 = `policy0`**；这个映射的来由见 [协议](../../cpu/protocol.md) 与 [单值基线](../../cpu/known-good.md) |
| `threshold` | `[mtk-cpu-table-mapping]` | 13 / 21 | m°C | 【推断】逐档降频的触发温度。数组长度 = 档数（1~11）。同一传感器和控制条件下，较低触发值可能更早限制；`20000` 为 20°C，不是无条件恒真，见 `thermal_policy_07` 首档与 `18` |
| `hysteresis` | `[mtk-cpu-table-mapping]` | 13 / 21 | m°C | 【推断】每档回滞。几乎全是 `2000`；例外：`thermal_policy_13` 第 7、8 档为 `1000`，`thermal_policy_08` / `09` 三簇全为单元素 `[1000]`（只有 `08` / `09` 的 CPU 表 disabled，`13` enabled） |
| `freq` | `[mtk-cpu-table-mapping]` | 13 / 21 | kHz | 【推断】每档对应的 **CPU 频率上限**。历史真机验证见 `docs/archive/cpu/frequency-ceiling-investigation-through-2026-09-26.md`。当前配置的频率数组随 threshold 单调不增，不作为所有 ROM 的语法要求 |
| `mode` | `[mtk-cpu-isolate]` | 13 / 21 | 枚举 enabled/disabled | 【推断】CPU 隔离开关；是否对应 offline 未验证。`enabled` 10 份、`disabled` 3 份（`thermal_policy_08` / `09` / `18`） |
| `sensor` | `[mtk-cpu-isolate]` | 13 / 21 | 字符串（传感器名） | 【推断】隔离控制读的温度源。`ap_ntc`(9) / `tz_game_shell`(4)。注意与同文件 `[mtk-cpu-table-mapping]` 的 sensor 可以不同（如 `thermal_policy_18` 频率表用 `tz_shell`、隔离用 `ap_ntc`） |
| `cpu` | `[mtk-cpu-isolate]` | 13 / 21 | CPU 名列表 | 【推断】要依次隔离的核。**12 处 `[cpu4, cpu5, cpu6, cpu7]`，1 处 `[cpu3, cpu5, cpu6, cpu7]`**（`thermal_policy_05`）—— 见 §3 异常清单 |
| `threshold` | `[mtk-cpu-isolate]` | 13 / 21 | m°C | 【推断】每个核被隔离的温度点。4 个值与 `cpu` 列表的 4 个核按位对应（`thermal_policy_05` 的 `cpu` 也是 4 项，能对上） |
| `hysteresis` | `[mtk-cpu-isolate]` | 13 / 21 | m°C | 【推断】每档回滞。多为全 `2000`；`thermal_policy_08` / `09` / `18` 的第 4 档为 `3000` |
| `mode` | `[mtk-gpu-table-mapping]` | 8 / 21 | 枚举 enabled/disabled | 【推断】GPU 频率表开关。**8 / 8 恒为 `enabled`**，无 `disabled` 样本 |
| `sensor` | `[mtk-gpu-table-mapping]` | 8 / 21 | 字符串（传感器名） | 【推断】GPU 频率表读的温度源。`ap_ntc`(3) / `tz_game_shell`(3) / `tz_shell`(2) |
| `threshold` | `[mtk-gpu-table-mapping]` | 8 / 21 | m°C | 【推断】逐档降 GPU 频的触发温度 |
| `hysteresis` | `[mtk-gpu-table-mapping]` | 8 / 21 | m°C | 【推断】每档回滞。**8 处每一个值都是 `2000`**。唯一异常是 `thermal_policy_07` 的第 6 项多一个空格（见 §3） |
| `freq` | `[mtk-gpu-table-mapping]` | 8 / 21 | **kHz（推断）** | 【推断】每档 GPU 频率上限。取值 `825000`…`260000`。**单位未验证** —— 按 CPU 侧同为单位推断为 kHz，但 GPU 也可能是 MHz 或不同量纲 |
| `HW_protection` | `[LTF-disable-throttling]` | 5 / 21 | 枚举 enabled/disabled | 【注释】段内注释原文："If HW_protection= disabled, it will turn off SW shutdown protections and LVTS HW reboot protection"。`enabled` 3 份（`disable_throttling` / `fix_ttj_85` / `fix_ttj_95`）、`disabled` 2 份（`disable_thermal` / `disable_thermal_temp`） |

**`mode` / `sensor` / `hysteresis` / `threshold` 这 4 个字段名在多个段里复用**，
但取值集合不同（尤其 `mode` 在 `[LTF]` 只有 `enabled`、在 `[vtskin-param]` 只有 `disabled`、
在 `[charger-cooler]` / `[mtk-cpu-*]` 两种都有）。**按段读，别按字段名读。**

---

## 2. 10 个段的清单

| 段 | 字段数 | 出现在几份文件里 | 备注 |
|---|---:|---:|---|
| `[policy_type]` | 1 | 21 | 只有 `permanent` |
| `[LTF]` | 2 | 16 | 5 份 `disable_*` / `fix_ttj_*` 用 `[LTF-disable-throttling]` 代替 |
| `[vtskin-param]` | 5 | 18 | 同名段一份文件里出现 1 / 3 / 7 次：**7 次 13 份**（`thermal`、`thermal_mtbf`、5 份 `disable_*`、2 份 `fix_ttj_*`、`01`、`02`、`07`、`12`、`13`），**3 次 3 份**（`08`、`09`、`18`），**1 次 2 份**（`10`、`11`） |
| `[mtk-skin-control]` | 11 | 21 | 字段最多的段 |
| `[mtk-cpu-table-mapping]` | 6 | 13 | 有表的 13 份每份三簇；其余 8 份无该段 |
| `[mtk-cpu-isolate]` | 5 | 13 | 缺该段的是 `thermal_policy_02` / `12` |
| `[mtk-gpu-table-mapping]` | 5 | 8 | GPU 映射表 |
| `[charger-cooler]` | 5 | 16 | 16 份有该段，其中 15 份带完整字段 |
| `[thermalhal-threshold]` | 4 | 21 | 免 root 读回通道 |
| `[LTF-disable-throttling]` | 1 | 5 | 仅 `disable_*` / `fix_ttj_*` |


---

## 3. 数据里的异常 / 易误判点

下面区分配置差异与尚未验证的解释：

| # | 现象 | 位置 | 判断 |
|---|---|---|---|
| 1 | `cpu= [cpu3, cpu5, cpu6, cpu7]`，其余 12 份全是 `cpu4` | `thermal_policy_05.conf` | 疑似笔误，也可能是该档特意隔离 `cpu3`。**未验证** |
| 2 | `hysteresis= [2000, 2000, 2000, 2000, 2000 ,2000]`，第 6 项逗号后多一个空格 | `thermal_policy_07.conf` | 纯格式噪声，解析应按空白切分 |
| 3 | `thermal_policy_02.conf` 的 `ref_sensor` 行内对齐空格三份不一致（`[  ap_ntc,` vs `[  ap_ntc, ` vs `[ ap_ntc,`） | `thermal_policy_02.conf` / `12` | 纯格式噪声 |
| 4 | `[mtk-cpu-table-mapping]` 段在 `thermal_policy_08` / `09` / `18` 里排在 `[charger-cooler]` **之后**，其余文件排在 `[mtk-cpu-isolate]` 之后 | 3 份 | 段顺序是否影响语义**未验证**（同名段重复出现，很可能按 `cluster=` 寻址而非按出现次序） |
| 5 | `thermal_policy_18.conf` 只有 3 个 `[vtskin-param]` 段（`vtskin1` / `vtskin2` disabled + `vtskin-max` maximum），`ref_sensor` 却引用从不定义的 `vtskin3`~`vtskin6`；`08` / `09` 同构 | `thermal_policy_18.conf`（`08` / `09` 相同） | `vtskin3`~`vtskin6` 在这些文件里没有对应段，取最大时输入是什么**含义未知** |
| 5b | `thermal_policy_10` / `11` 只有 1 个 `[vtskin-param]` 段（`vtskin1` disabled），连 `vtskin-max` 都没有 | `thermal_policy_10.conf` / `11` | `vtskin-max` 缺失时虚拟皮温从哪来**含义未知** |
| 6 | 21 份的 `[vtskin-param]` 显式 mode 共 89 处，全是 `disabled`，没有 `enabled` 样本 | 全部 | 无法从数据推断 `enabled` 的效果 |
| 7 | `[thermalhal-threshold] type` 21 份全是 `SKIN`，没有其它 `type` 样本 | 全部 | 无法推断还有什么 `type` |
| 8 | `[mtk-gpu-table-mapping]` 的 `freq` 单位没有第二手佐证 | 8 份 | 标为推断 |

---

## 4. 相关的其它文档

- [README.md](README.md) —— 本目录索引、文件清单、`02==12` / `08==09` 重复说明
- [app-whitelist.md](app-whitelist.md) —— 哪些 app / 场景选中哪个索引
- `docs/archive/cpu/frequency-ceiling-investigation-through-2026-09-26.md` —— 解码过程、字段含义的真机侧验证、选路逻辑
