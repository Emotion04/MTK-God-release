# V2430A 温控配置

本页是文件索引、格式定义和复核入口；运行结论见 [温控事实](../facts.md)。
厂商原件和明文只存本机忽略目录 `.local-baselines/thermal-crack/`，不在文档里复制配置。

依据：2026-10-05，V2430A / [当前 ROM](../../VERSIONS.md)，ADB shell 只读比较
`/vendor/etc/thermal/` 的 21 份原件与本地 SHA-256，全部一致；本地解码往返与明文逐字节核对通过。
这确认配置基线，没有重启设备或重新触发相机、游戏等场景。

## 解码算法

当前 ROM 使用逐行模 91 移位，字母表为 `0x20–0x7A`（91 个字符）。
`i` 是行内字节下标，从 0 开始；LF (`0x0A`) 原样透传并复位 `i`。

```text
cipher[i] = 32 + ((plain[i] - 32 + (i % 10)) % 91)
plain[i]  = 32 + ((cipher[i] - 32 - (i % 10)) % 91)
```

[decode.py](decode.py) 按字节实现。21 份文件满足 `encode(decode(x)) == x`；
可逆性不单独证明明文语义，格式仍需结合段名、配置结构和可用读回核对。
新 ROM 先验证，不直接假定算法相同。推导过程见 [历史调查](../../archive/cpu/frequency-ceiling-investigation-through-2026-09-26.md)。

## 文件索引

13 份 `thermal_policy_NN.conf` 是策略索引对应文件；另有 8 份主配置与特殊配置。
“文件存在”“被选中”“动作生效”分别判断。选路来源见 [app-whitelist.md](app-whitelist.md)，
字段统计与未知语义见 [field-reference.md](field-reference.md)。

表中频率为 cluster2（当前 policy7）的首档配置，单位 kHz；不等于所有温度下的上限。
`permanent` 的重启含义来自厂商注释，未逐项验证重启后的加载行为。

| 策略 | permanent | skin-control 源 | cluster2 首档 | 档数 | GPU 表 | isolate 段 |
|---|---|---|---:|---:|:---:|:---:|
| 00 | No | tz_game_shell | 2600000 | 6 | 有 | 有 |
| 01 | No | ap_ntc | 无频率表 | 0 | 无 | 有 |
| 02 | No | tz_shell | 2300000 | 2 | 有 | 无 |
| 03 | No | ap_ntc | 1700000 | 4 | 无 | 有 |
| 05 | No | tz_game_shell | 2850000 | 4 | 无 | 有 |
| 07 | Yes | ap_ntc | 3300000 | 8 | 有 | 有 |
| 08 | No | tz_shell | 表 disabled | 0 | 无 | 有 |
| 09 | No | tz_shell | 表 disabled | 0 | 无 | 有 |
| 10 | No | tz_game_shell | 2800000 | 6 | 有 | 有 |
| 11 | No | tz_game_shell | 2000000 | 3 | 无 | 有 |
| 12 | No | tz_shell | 2300000 | 2 | 有 | 无 |
| 13 | Yes | ap_ntc | 2600000 | 9 | 有 | 有 |
| 18 | No | tz_shell | 2100000，阈值 20°C | 1 | 无 | 有 |

02 与 12、08 与 09 各自逐字节相同，13 个名称对应 11 种内容。
相同内容不证明加载器把文件名视为等价。
当前没有 04 / 06 / 14 / 15 / 16 / 17 / 19 对应文件；静态选路中仍有索引 17 的引用。

| 其他文件 | permanent | 配置差异 |
|---|---|---|
| `thermal.conf` | Yes | 主配置，CPU 三簇表；cluster2 11 档从 28°C 开始，首档 3100000 |
| `thermal_mtbf.conf` | Yes | 无 CPU/GPU 频率表，ATC 最大目标 105000 |
| `disable_skin_control.conf` | No | 目标结温 min/max 均 95000 |
| `disable_thermal.conf` | Yes | 目标结温和 SKIN 阈值 300000，保护字段 disabled |
| `disable_thermal_temp.conf` | No | 与上一份只差 permanent 字段 |
| `disable_throttling.conf` | No | SKIN 阈值 80000，保护字段 enabled |
| `fix_ttj_85.conf` | No | 目标结温 min/max 均 85000 |
| `fix_ttj_95.conf` | No | 目标结温 min/max 均 95000 |

特殊文件的加载入口尚未确认；历史搜索未找到入口不证明只能通过某个 socket 或 root 加载。
文件名和高阈值也不证明运行中已关闭所有温控。

## 重取与核对

本地布局：`raw/thermal/` 保存原件，`plain/` 保存对应明文。
在仓库根目录拉取到新的临时目录，核对 ROM 与摘要后再决定更新基线：

```text
adb pull /vendor/etc/thermal/ .local-baselines/thermal-check/raw/
adb shell sha256sum /vendor/etc/thermal/*.conf
```

新快照与旧快照比较配置内容，不只比较时间。设备条件不同时，按 [版本适配](../../VERSIONS.md)
保留仍被支持的差异，不整套复制文档。

当前代表摘要：

| 文件 | SHA-256 |
|---|---|
| `thermal.conf` | `d082f86b8a84f3ed9150035a176aca652f06c868f716b1df5a0f6e3af72770d5` |
| `thermal_policy_01.conf` | `f2b6c184fdd7d8f503d1796ec10a59cd2932cbf571335794fbbaa60e1405d3e1` |

查看明文：

```text
python docs/thermal/configs/decode.py .local-baselines/thermal-crack/raw/thermal/thermal.conf
```

保存逐字节明文时调用 `decode_bytes()` 并用 `Path.write_bytes()`；
Windows stdout 或 shell 重定向可能改变 LF，造成假差异。使用 `encode_bytes()`
检查往返，再和已有明文比较。行数需计入末尾没有换行的一行。

无末尾换行的 8 份文件：policy 00 / 05 / 10 / 11、
`disable_thermal_temp.conf`、`disable_throttling.conf`、`fix_ttj_85.conf`、`fix_ttj_95.conf`。
这是原件状态，不自行补换行。

核对运行状态可读取 `dumpsys thermalservice`，区分缓存、HAL 当前读数和覆盖标记。
2026-10-05 SKIN 阈值为 39 / 41 / 44 / 47 / 60 / 80，覆盖标记 false；
它与主配置相符，但阈值组并非每个策略都唯一，不能仅凭该组证明加载了哪份文件。
更完整的观测解释见 [THERM-011](../facts.md#therm-011)。

