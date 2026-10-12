# 温控调查档案

> 历史材料。这里保留截至 2026-09-26 的测试过程、分支与被替代解释。
> 当前结论只认 [温控事实](../thermal/facts.md)。

本文件记录 vivo PD2430A 上温控相关的实测结论，以及未来「温控中心」的目标形态。
本文只用于追溯温控调查细节；命令现状见 `docs/device/commands.md`。

## 1. 两层结构，互相独立，不是取最小值

### 第一层：每个传感器各自的节流档

`dumpsys thermalservice` 输出每个 `Temperature` 自带 `mStatus`。**同一时刻各传感器档位不同**：

```
Temperature{mValue=66.416, mType=0,  mName=CPU,            mStatus=0}
Temperature{mValue=44.5,   mType=2,  mName=BATTERY,        mStatus=0}
Temperature{mValue=47.444, mType=3,  mName=SKIN,           mStatus=4}
Temperature{mValue=51.676, mType=5,  mName=POWER_AMPLIFIER, mStatus=0}
Temperature{mValue=60.038, mType=9,  mName=NPU,            mStatus=0}
Temperature{mValue=60.038, mType=10, mName=TPU,            mStatus=0}
Temperature{mValue=60.038, mType=13, mName=SOC,            mStatus=0}
```

本机 8 个传感器：CPU(0) / GPU(1) / BATTERY(2) / SKIN(3) / POWER_AMPLIFIER(5) / NPU(9) / TPU(10) / SOC(13)。
**单位是摄氏度，不是毫度。** 类型编号与 Android `IThermal` AIDL 3 的 `TemperatureType` 对齐。

各传感器管的事不同 —— SKIN 主要管屏幕亮度，CPU 管频率，BATTERY 管充电。
所以"木桶效应"只在行为上像，**触发点和作用对象都不一样**。

### 第二层：框架统一档位

`Thermal Status` 即 `IThermal.getCurrentThermalStatus()`，AOSP 定义 0–6：

| 值 | 含义 |
|---|---|
0 | 无限制 |
1 | 轻度限流 |
2 | 中度限流 |
3 | 重度限流 |
4 | 严重 |
5 | 紧急 |
6 | 关机 |

`IsStatusOverride` 表示这一层是否被锁：`false` = 系统按实测自己决定，`true` = 被 `override-status` 锁住。

## 2. 两个命令的性质完全不同，别混用

`cmd thermalservice` 提供：

```
override-status STATUS     sets and LOCKS the thermal status of the device to STATUS
reset                      unlocks the thermal status
inject-temperature TYPE STATUS NAME [VALUE]
headroom FORECAST_SECONDS
```

| 命令 | 性质 | 持续开销 | 适用 |
|---|---|---|---|
`override-status N` | **锁存**，一次调用一直生效 | **无** | 放宽温控 |
`inject-temperature` | **一次性注入**，下一轮采样被真实温度覆盖 | **要持续注入才有效 → 额外唤醒 → 额外耗电** | 不推荐 |

**用户最初担心"改一次会被复写回去"正是 `inject-temperature` 的问题，`override-status` 没这个问题。**
要做只做锁存。

## 3. 实测：`override-status` 有效，但它只管第二层

**2026-09-24 实测：**

```
覆写前：Thermal Status: 1   IsStatusOverride: false
cmd thermalservice override-status 0
覆写后：Thermal Status: 0   IsStatusOverride= true     ← 确实锁住了
...cpu7 满载 22 秒...
结果：  policy7 最高仍是 2300000，与不覆写时完全一致
cmd thermalservice reset
复位后：Thermal Status: 1   IsStatusOverride= false     ← reset 有效
```

**结论：`override-status` 能锁住第二层，但锁到 0 也解不开 CPU 频率上限。**
说明当时起约束作用的是第一层 / vendor 自己的温控引擎，不是框架这一层。

`am force-stop thermal-engine-v2` 已试过：**服务不存在，空操作** —— 这台机器的 vendor 温控
不是以那个名字跑的，找不到入口。

## 4. **频率上限与温控无关**（重要，别做错误预期）

完整调查线见 [cpu-freq-ceiling.md](cpu/frequency-ceiling-investigation-through-2026-09-26.md)。

2026-09-24 对照实验：

| 条件 | policy7 实际最高 |
|---|---|
无相机、无负载（空闲采样） | 2300000 |
无相机、**cpu7 满载 22 秒** | **2300000** |
无相机、满载 + **温控强制 0** | **2300000** |
**开着相机** | **3400000**（硬件满频） |

按簇对比：

| 簇 | 硬件上限 | 纯负载实测最高 | 差距 |
|---|---|---|---|
policy0 | 2000000 | — | — |
policy4 | 2850000 | 2400000 | 450 |
policy7 | 3400000 | 2300000 | 1100 |

**已排除的成因**：温控档位（含强制 0）、省电模式（`low_power=0`、Battery Saver OFF）、
未开 boost（`power_save_type=6` 三键齐）、硬件上限（实测达到过 3400）。

**结论：2.3G 是针对普通进程的按簇钳制，vendor 自己的场景能突破。**
这与温控是两件事 —— **未来的温控中心不会解除这个上限。**
排查方向转到：vivo boost 框架（`vendor.boostfwk.*`）、PowerHAL 簇表 `freqMax`、
常驻场景下发的 perfLock 上限。

## 5. 「温控中心」目标形态

用户提出的需求，按优先级：

1. **一键去除所有温控** —— 目标是把两层都压到 0。第二层有 `override-status 0`，
   第一层/vendor 层暂无入口（`force-stop` 无效）。**未解决问题。**
2. **温和放宽温控** —— 不做一刀切，而是设一个**档位封顶**：
   实际 3 档 → 上报 2 档；实际 4 档 → 上报 3 档。
   保留部分保护，只把激进的节流缓和一档。接口上用 `override-status <封顶值>`。
   **注意：这只影响第二层**，第一层不受它管。
3. **每个主要传感器单独控制** —— 第一层没有找到公共覆写接口，未解决。
4. **显示全部传感器温度与各自档位** —— 已有数据源（`dumpsys thermalservice`），
   App 的 `HostMetrics` 已显示温度和节流档，可扩展到列出全部 8 个传感器。

### 待验证

- 第二层设置不同封顶值（1/2/3）分别有什么可感知效果
- 第一层是否存在任何可写入口（包名 / prop / 服务）
- `inject-temperature` 是否需要额外权限（未测）

## 6. 策略文件活在用户态，kernel thermal framework 管不到 CPU

2026-09-26 查证，结论：**CPU 限频不走 Linux thermal framework。**

| 证据 | 说明 |
|---|---|
| `/sys/class/thermal/cooling_device*` 有 6 个，**没有一个对应 cpufreq** | devfreq(GPU)、lcd-backlight、wifi、flashlight。没有任何 cpufreq cooling device |
| 每个 `thermal_zone*` **只有一个 trip**（`118400` 或 `125000`，type=critical/passive） | 那是硬件 hard shutdown 阈值，不是限频阶梯 |
| `thermal_zone*/policy` 78 个全是 `step_wise` | 这是 Linux thermal governor，与 `thermal_policy_NN.conf` 零关联 |

`thermal_policy_NN.conf` / `0x03000000` 整套活在 **PowerHAL 用户态**（libmtcloader → PPM limit + HAL skin threshold）。kernel 那一层只当粗粒度 hard guard。

**读 `/sys/class/thermal/` 前注意**：SELinux 拦的是 `sysfs_thermal` 的 search/read，目录本身 `0755 root:root` 并没有问题。绕道 `/sys/devices/virtual/thermal/` **完全可读**（同一批 sysfs 对象的另一条 symlink，SELinux 漏放）—— 但如上，读到的内容与温控策略无关。

## 7. 判断当前生效哪份策略：只有 SKIN 阈值一条路

`dumpsys thermalservice` 的 `Temperature static thresholds from HAL:` 里 `mName=SKIN` 那一行，
就是当前生效那份 `.conf` 的 `[thermalhal-threshold] type=SKIN` 段。

**这是唯一确认可用的读回通道**，依据是切换策略时的全量 diff：41 组 ↔ 40 组切了四个来回，
整份 dump 只有三处不同 —— listener 回调数（注册噪声）、8 行实时温度、以及 SKIN 那一行。

识别到**组**级，组内分不出来：

| 阈值组 | 文件 |
|---|---|
| `41/44/46/48/60/80` | thermal_policy_01 / 08 / 09 |
| `40/42/45/48/60/80` | 00 / 02 / 05 / 07 / 10 / 11 / 12 / 13 / 18 |
| `39/41/44/47/60/80` | thermal.conf |
| `40/43/45/47/60/80` | thermal_policy_03 |
| `54/60/64/80` | thermal_mtbf.conf |
| `30/30/30/30` | disable_thermal / disable_thermal_temp |
| `80/80/80/80` | disable_throttling / disable_skin_control / fix_ttj_85 / fix_ttj_95 |

**试过但补不上这个缺口的手段**（2026-09-26，别再重复）：

- `thermal_zone*/trip_point_N_temp` —— 硬件 hard shutdown 阈值，真实策略切换下**一个比特都不动**。
- `thermal_zone*/policy` —— `step_wise`，是 thermal governor，与策略文件无关。
- `scaling_max_freq` 逐簇 —— 策略切换下 policy4/7 恒等于硬件满频，纹丝不动。
- `dumpsys thermalservice` 除 SKIN 行以外的部分 —— 见上文全量 diff。
- `getprop vendor.thermal.manager.data.perf`、`/proc/ppm/*` —— 空或不存在。

## 8. 相机是否切策略：两次实测相反，触发条件未定位

| 测的人 | 条件 | 结果 |
|---|---|---|
| 本项目（2026-09-26 02:01） | 索引 1 长期保持 + 范围 2900000–3400000 在效 | 相机前台 SKIN **41 组 → 40 组**，退出还回，三轮闭环 |
| 取证子任务（2026-09-26 02:2x） | App 无任何请求 | 相机前台 90 秒，SKIN **全程 41.0，一次都没切** |

**两次都是实测，结论相反，尚未定位触发条件。** 可疑方向：App 当时有没有在效请求、
`am start` 打开的相机与用户手动打开的是否同一路径、以及前台时长。

**在定位之前，不要拿"相机会切策略"当已知事实用。** 已稳定复现的策略切换是**游戏 App**
（`com.netease.onmyoji.vivo`：41↔40 切了四个来回，SKIN 阈值每次同步变）。

## 9. **2.3 墙的出处：主模 thermal.conf 的温度阶梯**（2026-09-26 02:50）

`other/thermal.conf`（主模）cluster2（超大核）的频率梯：

```
threshold= [28000, 32000, 35000, 37000, 40000, 42000,
            44000, 46000, 48000, 50000, 52000]      ← 毫摄氏，即 28~52 °C
freq=     [3100000, 3000000, 2400000, 2300000, 2100000, 2000000,
            1800000, 1400000, 1200000, 1200000, 1100000]
```

**皮温 37 °C → 2300000。** 用户报告的"这个系统版本一直锁 2.1–2.3 GHz"正是这一档 ——
皮温常驻 37 °C 左右就会稳定落在第 4 级。

同时本机实测到 SoC 结温与皮温**相差约 40 °C**：

```
CPU/GPU/NPU/SOC/TPU  mValue=78.0xx°C  mStatus=0
SKIN                 mValue=37.9°C    mStatus=0
```

**所以"哪份策略在生效"决定上限，而策略之间最大的差别是看哪个传感器、
有没有 CPU 频率表**：

| 组 | 传感器 | cluster2 频率梯 | 皮温 37°C 时的上限 |
|---|---|---|---|
| 主模 thermal.conf | ap_ntc / vtskin-max | 11 级，28 °C 起 | **2300000** ← 用户的墙 |
| thermal_policy_00 | tz_game_shell | 6 级，37 °C 起 | 2600000 |
| thermal_policy_01 | ap_ntc | **无频率表** | **3400000**（硬件满频） |
| thermal_policy_08 / 09 | tz_shell | 有段但 disabled | 3400000 |

## 10. ~~当前为何是满频~~ → 已解决（2026-09-26 02:55 重启后实测）

**§28 与下面第 10 节旧文的两种解释，答案是第 2 种：残留请求。**

2026-09-26 02:43 实测：无 App 前台、`am force-stop` 之后仍连采 8 次全部 **3400000**，
SKIN 阈值 = `41/44/46/48/60/80`（即 01/08/09 组）。

两种解释，**都未排除**：

1. 系统此刻确实在用 01/08/09 那一组（该组无 CPU 频率表 → 满频）；
2. **我们早前下发的索引 1 没释放干净**，而 `am force-stop` 杀不掉
   Shizuku 的 UserService（实测 pid 11237 在 force-stop 后仍存活，handle 由它持有）。

**区分这两者只有一个可靠办法：重启手机**（重启会清掉全部 handle）。未做 —— 需要用户同意。

**在此之前，"默认态 = 硬件满频"这条结论只能算条件性成立** ——
它成立的前提是当前生效组确为 01/08/09，而不是残留请求。


### 10.1 重启后的纯净态（决定性）

重启 + 等系统稳定后，App 只刚起来、无任何请求：

```
SKIN 阈值 = [39.0, 41.0, 44.0, 47.0, 60.0, 80.0]   ← thermal.conf 主模
皮温 = 41.6 °C
policy7 scaling_max_freq 实测 = 2100000（连续两次）
```

**主模在皮温 41.6 °C 时把超大核锁到 2.1 GHz** —— 与 §9 的频率梯第 5 档
（40000 → 2100000）完全对上，也与用户长期报告的"锁 2.1–2.3 GHz"一致。

### 10.2 因此修正的结论

| 之前的说法 | 修正后 |
|---|---|
| "默认态 = 硬件满频，索引 1 什么都没抬" | **错。** 默认态是主模，皮温 40 °C 附近锁 2.1 GHz |
| 索引 1 有价值吗 | **有。** 它换上无 CPU 频率表的那一组，实测能到 3400000 |

**索引 1 是我们目前唯一确认能把上限从系统默认状态下解开的手段。**

### 10.3 两个仍要记住的附加事实

1. **重启能清掉我们的请求，但不保证系统回到某个特定状态** ——
   HAL handle 随进程消亡，不过策略是否持久还要看 `permanent=` 标志
   （本机 13 份里 07 和 13 是 `permanent=Yes`）。本次只用过 1，非 permanent。
2. **皮温是主模的输入**：37 °C → 2300000，41.6 °C → 2100000。
   同样的主模，皮温不同档位不同 —— 这就是"上限是动态的"的成因。

## 11. boost 模式与 power_save_type 都不切温控策略（2026-09-26 03:10）

用户手动切换 vivo boost 模式前后各测，本机功耗位确实随之变化，但**温控策略组一次都没动**：

| 状态 | SKIN 阈值组 | `power_save_type` | 皮温 | `p7cur` 峰值 |
|---|---|---|---|---|
| 重启后纯净态 | 主模 `39/41/44/47/60/80` | 6 | 41.6°C | 2100000 |
| boost 关 | 主模 `39/41/44/47/60/80` | **1** | 36.2°C | 2000000 |
| `power_save_type` 改回 6 | 主模 `39/41/44/47/60/80` | 6 | 37.0°C | 2000000 |

**结论**：
1. **boost 模式不换温控策略文件。** 切换 boost 只改 `power_save_type`（6→1），SKIN 组始终是主模。
2. **`power_save_type` 的 1 与 6 对策略和频率都没有可测影响**（SKIN 组、p7cur 峰值完全相同）。
3. 因此"boost 开着时拿到了 3400000"**不能**归因为 boost 切了策略 —— 那次更可能是温控策略索引 1 的残留（见 §10）。

**对功能的影响**：boost 与我们的「温控策略索引 + 频率范围」走的是不同机制，**互不覆盖**。
用户切 boost 不会破坏索引+范围的调控。

## 12. App 显示与真机读数一致（抽查）

App 首页「当前方案 / 簇状态」与 adb 读数交叉核对：

| App 显示 | adb 读数 | 一致 |
|---|---|---|
| 「未套用方案」（无在效请求） | 开机后 `0x03000000` 下发 0 次、PerfLock 无在效 handler | ✅ |
| policy7 簇状态频率 | `scaling_cur_freq` 仍在 600K–2G 浮动 | ✅ |

温控策略索引卡片上的「当前皮肤阈值」一行为 `dumpsys thermalservice` 同源解析，
与本节表格的 SKIN 组读数同口径，**未逐字截图核对**（导航到该卡片需进 调校→系统配置）。


## 13. 为什么"发命令开 boost"是错的（2026-09-26）

试过三条路，全不切温控策略。根因是**三方说的"boost"不是同一个东西**：

| 来源 | 指的什么 | 本机结果 |
|---|---|---|
| `cmd power set-fixed-performance-mode-enabled true` | AOSP 标准性能模式 | 命令成功，SKIN 组不变 |
| `settings put system cpu_boost_mode 1` | vivo 智算平台候选键（出自 X80 机型分析） | **本机该键为 null，不存在**。见 [tools/vivo-smart-platform.md](../tools/vivo-smart-platform.md) 第 11 行 —— 那份文档本来就标了"不是当前设备的实测证据" |
| 本 App 的「重度游戏 boost」（`data/tweaks/GameBoost.kt`） | Android 原生机制：appops / Doze 白名单 / 待机分桶 | 能用，因为它走的是真接口 |

App 里那个 boost **根本没碰频率**，源码注释写得很清楚：

> **不做** `cmd game mode performance` 与 `game_overlay` 帧率覆写 —— 那两者让平台重推
> 自己的一套性能策略，会和本软件的 CPU 频率句柄打架（PowerHAL 合并规则只能收紧）。
> 这里只做"别限制它"，不动频率。

**vivo 真正的 boost 模式**（用户在快捷开关/游戏中心手点那个）走
`power_save_type` + `monster_mode_<pkg>` 一族，语义未确认，
当时的档位值未取得有效证据。固定样本说明见 [kbattery](../reference/apps/kbattery.md)，当前设备结论见 [温控事实](../thermal/facts.md)。

结论：**要开 boost 请用户手点**，App 不代劳 —— 这不是没做，是做了会更错。

## 14. 温度 → 频率 → 索引 对照表（2026-09-26 整理）

**这是"系统默认状态下，皮温多少对应大核多少 MHz"的完整答案。**

### 14.1 索引归属

| 状态 | 生效的配置文件 | 有 CPU 频率表？ |
|---|---|---|
| **系统默认（重启后）** | `thermal.conf`（主模） | **有**，11 级 |
| 温控策略索引 1 / 08 / 09 | `thermal_policy_01` / `08` / `09` | **无**（01 连段都没有；08/09 有段但 disabled） |

SKIN 阈值可用来区分这两类：`39/41/44/47/60/80` = 主模，
`41/44/46/48/60/80` = 索引 1/08/09 那一组。

### 14.2 主模的频率梯（cluster2 = 超大核）

```
传感器   ap_ntc / vtskin-max
threshold= [28000, 32000, 35000, 37000, 40000, 42000,
            44000, 46000, 48000, 50000, 52000]   毫摄氏
hysteresis= 每级 2000（2 °C 回差）
freq=      [3100,   3000,   2400,   2300,   2100,   2000,
            1800,   1400,   1200,   1200,   1100]   MHz
```

也就是：

| 皮温 | 大核上限 |
|---|---|
| 28–32 °C | 3100 MHz |
| 32–35 °C | 3000 MHz |
| 35–37 °C | 2400 MHz |
| **37–40 °C** | **2300 MHz** ← 用户报告的"锁 2.3" |
| 40–42 °C | 2100 MHz |
| 42–44 °C | 2000 MHz |
| 44–46 °C | 1800 MHz |
| 46–48 °C | 1400 MHz |
| 48–50 °C | 1200 MHz |
| 50–52 °C | 1200 MHz |
| >52 °C | 1100 MHz |

### 14.3 真机数据点核对（已有的）

| 皮温 | 实测 `policy7 scaling_max_freq` | 梯子预测 | 对得上？ |
|---|---|---|---|
| **36.04 °C**（2026-09-26 03:36，无负载） | `scaling_cur_freq` 600K–1.6G 浮动 | 2400000（35–37 档） | 无负载，Governor 自选低频，**不能验证上限** |
| 36.04 °C + 满载（03:40，同一次会话） | **2100000**（6 次全稳定） | 2400000 | ⚠️ 低一档。`hysteresis=2000` 只解释一半 |
| **42.92–43.45 °C** + 满载（03:41，皮温被负载推高） | **2000000**（12 次全稳定） | **2000000**（42–44 档） | ✅ **完全吻合** |
| ~~36.0 °C~~ | ~~3000000~~ | — | **旧数据作废** —— 那次测得 3000000 时策略组状态未记录，可能是残留 |

> **无负载时 `scaling_cur_freq` 不能用来验证上限** —— Governor 自己会选低频。
> 验上限必须先加负载（App 性能页的加满载负载）。
> 本机 `scaling_max_freq` 权限位时通时断，读不到是常态。
| 40.2 °C | 2000000 | 2100000（40–42 档） | ⚠️ 差一档，40.2 已过 40+2 回差，落 42 档就对上了 |
| 41.6 °C | 2100000 | 2100000（40–42 档） | ✅ |
| 46.5 °C（满载） | 2900000（索引 12 在效） | — | 不同策略，不适用 |

**结论：梯子在 42 °C 档与真机完全吻合**（2000000 = 表值）。
36 °C 档实测低一档（2100000 vs 2400000），`hysteresis=2000` 只能解释一部分，**余下差异未定位**。

真机验证条件（照这个复现）：App 在前台（小窗不算切走，实测 SKIN 组不变）、
开着 App 的加满载负载、皮温会被推到 43 °C 左右，此时读 `policy7 scaling_cur_freq`。

唯一要注意的是 `hysteresis=2000` ——
越过阈值后要回落 2 °C 才升档，所以实测值常比"看温度猜"的低一档。

### 14.4 还没做的

- **逐档系统性真机验证**：需要能主动把皮温稳定在各档，
  自然冷却/加负载都不够精确。目前只有 4 个零散数据点。
- **另两簇（policy0 / policy4）的梯子**：文件里都有，未逐档验。
- `policy0 scaling_max_freq` 全程 Permission denied，没法读。

### 14.5 所以"多少度给多少频率"的答案

**默认态下由 `thermal.conf` 这张 11 级表决定，皮温是唯一输入。**
要摆脱它只有两条路：换一份没有频率表的策略（索引 1/08/09），
或者——还没找到的——让 HAL 加载 `disable_*.conf`（已确认无入口，见 §7）。

