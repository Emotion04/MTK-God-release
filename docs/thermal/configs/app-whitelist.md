# 每个索引的 app 白名单

<!-- external-symbols -->
本页通篇引用厂商配置里的应用名与活动名，那些符号不属于本项目源码。

`/vendor/etc/thermal/thermal_policy_NN.conf` 不会自己跑起来 —— 得有人**选中**它。
本文摘录两份配置中引用温控策略索引的位置，用于核对选路；其他运行时消费者可能不在这两份表中。

**数据来源与最近复核**

| 来源 | 文件 | 大小 | 选中依据 | 条数 |
|---|---|---:|---|---:|
| A | `/vendor/etc/power_app_cfg.xml` | 149,758 字节 / 2434 行 | **前台包名 / Activity / FPS / WINDOW** | **24** |
| B | `/vendor/etc/powerscntbl.xml` | 58,861 字节 / 1011 行 / 95 个 `<scenario>` | **powerhint 场景 ID** | **12** |

两份表的工作副本位于 Git 忽略的本地目录：`power_app_cfg.xml` 在 `.local-baselines/policy-select/`，
`powerscntbl.xml` 在 `.local-baselines/pull/`。解析器是
`policy-select/parse_appcfg.py` 和 `policy-select/parse_scn.py`
（本文所有条数和行号都由这两个脚本跑出来，可复现，见 §5）。

2026-09-26 在手机 `V2430A` / `PD2430D_A_16.1.12.0.W10`、ADB shell uid 2000 下复核，
手机文件与本地工作副本 SHA-256 完全一致：

| 手机文件 | SHA-256 |
|---|---|
| `/vendor/etc/power_app_cfg.xml` | `769353d15f4728d01de3bbc7f02ac07570c820fdef329f51c6d4787b922bea22` |
| `/vendor/etc/powerscntbl.xml` | `b3e3ff55cdba1e2642460d9434a896d2c7c54e7af17a8b827fa3546ad1f3b857` |

2026-09-26 解析器分别得到 24 条和 12 条引用，与下表一致。2026-10-05 再核对手机两份文件，哈希仍一致。
哈希验证的是静态配置快照；
它不证明某个 app 或场景当前正在触发该策略。应用显示名仍是辅助标注，包名才是配置原文。

需要刷新忽略目录中的副本时，从仓库根目录执行：
`adb pull /vendor/etc/power_app_cfg.xml .local-baselines/policy-select/` 和
`adb pull /vendor/etc/powerscntbl.xml .local-baselines/pull/`；随后重算哈希并复跑 §5 的解析器。

**`param1` 就是索引**：命令统一是 `cmd="PERF_RES_THERMAL_POLICY" param1="N"`，
HAL 据此加载 `/vendor/etc/thermal/thermal_policy_%02d.conf`。
索引 → 文件名的对应来自固件模板与真机调查，历史过程见
`docs/archive/cpu/frequency-ceiling-investigation-through-2026-09-26.md`。

---

## 0. 总表：13 份文件 vs 36 处引用

两张表**去重后的引用索引**：`{0, 3, 5, 7, 8, 9, 10, 11, 12, 13, 17, 18}`（12 个）
设备上**实际存在的文件**：`{00, 01, 02, 03, 05, 07, 08, 09, 10, 11, 12, 13, 18}`（13 个）
**引用条目合计**：24 + 12 = **36 条**（索引 0/3/5/10 这 4 个被两张表同时引用）

| 索引 | 文件在吗 | 来源 A（app） | 来源 B（powerhint） | 合计 |
|---|---|---|---|---:|
| 0 | 有 | 2 条 | 1 条（2003） | **3** |
| 1 | 有 | 0 | 0 | **0** |
| 2 | 有 | 0 | 0 | **0** |
| 3 | 有 | 1 条 | 1 条（2180） | **2** |
| 4 | **无文件** | — | — | — |
| 5 | 有 | 1 条 | 4 条（2004/2005/2008/2009） | **5** |
| 6 | **无文件** | — | — | — |
| 7 | 有 | 0 | 2 条（1150/2150） | **2** |
| 8 | 有 | 12 条 | 0 | **12** |
| 9 | 有 | 5 条 | 0 | **5** |
| 10 | 有 | 1 条 | 1 条（2000） | **2** |
| 11 | 有 | 0 | 1 条（2002） | **1** |
| 12 | 有 | 1 条 | 0 | **1** |
| 13 | 有 | 0 | 1 条（2010） | **1** |
| 14 / 15 / 16 | **无文件** | — | — | — |
| 17 | **无文件** | **1 条** | 0 | **1** ⚠️ 见 §4 |
| 18 | 有 | 0 | 1 条（2001） | **1** ⚠️ 见 §3 |

**两个不对称，都值得记住：**

1. **索引 1 和 2 两张表都不引用**，但文件存在。索引 1 已通过 App 直发
   `0x03000000` 手动触达；干净启动默认使用主 `thermal.conf`，见 `THERM-001`。
   索引 2 的孪生 12 被相机选中；索引 2 的固件自动入口仍未找到。
2. **索引 17 被引用但设备上没有 `thermal_policy_17.conf`** —— 见 §4。

---

## 1. 来源 A：`power_app_cfg.xml`（按前台包名）

**24 条全列。** `WINDOW` 列 `Common` / `Single` / `Multi` 是 XML 里的窗口模式属性；
`（无 WINDOW 层）` 表示该 `<data>` 直接挂在 `<FPS>` 下，外面没有 `<WINDOW>` 元素。
"块内 data 条数"指**装载温控这一条的那个 `<WINDOW>`（或 `<FPS>`）块里一共有几条 `<data>`**。

| # | 索引 | 包名 | Activity | FPS | WINDOW | XML 行号 | 块内 data 条数 | 组 · 应用名 | 同块其它温控相关命令 |
|---:|---:|---|---|---|---|---:|---:|---|---|
| 1 | **3** | `com.miHoYo.enterprise.HK4E` | `Common` | Common | Common | L412 | 3 | 游戏 · 原神国际服 | — |
| 2 | **0** | `com.netease.moba.vivo` | `Common` | Common | （无 WINDOW 层） | L480 | 5 | 游戏 · 决战平安京 vivo 渠道 | — |
| 3 | **5** | `com.kurogame.mingchao` | `Common` | Common | Common | L1012 | 18 | 游戏 · 鸣潮 | `FRS_TARGET_TEMP`=45000 |
| 4 | **0** | `com.netease.mrzh` | `Common` | Common | （无 WINDOW 层） | L1260 | 10 | 游戏 · 明日之后 | `FBT_LIMIT_CFREQ`=2300000、`FBT_LIMIT_CFREQ_M`=2050000、`FBT_LIMIT_RFREQ`=2500000、`FBT_LIMIT_RFREQ_M`=2350000、`FPSGO_THRM_ENABLE`=0 |
| 5 | **12** | `com.android.camera` | `com.android.camera.CameraActivity` | Common | Common | L1329 | **1** | 系统 · vivo 相机 | — （整块只有温控这一条） |
| 6 | **8** | `io.github.devriesl.raptormark` | `Common` | Common | Common | L1408 | 19 | 跑分 · RaptorMark 存储/内存基准 | `THERMAL_SPORTS_CG_POLICY`=1、`POWERHAL_SCREEN_OFF_STATE`=3、`SCHED_UCLAMP_MIN_TA`=50 |
| 7 | **8** | `com.antutu.ABenchMark` | `Common` | Common | **Single** | L1435 | 20 | 跑分 · 安兔兔实验室版 | `THERMAL_SPORTS_CG_POLICY`=1、`POWERHAL_SCREEN_OFF_STATE`=3、`SCHED_UCLAMP_MIN_TA`=50 |
| 8 | **9** | `com.antutu.benchmark.full` | `Common` | Common | **Single** | L1453 | 9 | 跑分 · 安兔兔 | `THERMAL_SPORTS_CG_POLICY`=1、`POWERHAL_SCREEN_OFF_STATE`=3 |
| 9 | **8** | `com.antutu.benchmark.full` | `com.android.module.graphics.video.VideoCTSTestActivity` | Common | Common | L1475 | 14 | 跑分 · 安兔兔（视频 CTS 子项） | `POWERHAL_SCREEN_OFF_STATE`=3、`SCHED_UCLAMP_MIN_TA`=50 |
| 10 | **8** | `com.antutu.benchmark.full` | `com.android.module.graphics.video.VideoDecodeTestActivity` | Common | Common | L1495 | 14 | 跑分 · 安兔兔（视频解码子项） | `POWERHAL_SCREEN_OFF_STATE`=3、`SCHED_UCLAMP_MIN_TA`=50 |
| 11 | **8** | `com.antutu.benchmark.full` | `com.android.module.graphics.video.VideoEditingActivity` | Common | Common | L1515 | 14 | 跑分 · 安兔兔（视频编辑子项） | `POWERHAL_SCREEN_OFF_STATE`=3、`SCHED_UCLAMP_MIN_TA`=50 |
| 12 | **8** | `com.primatelabs.geekbench5` | `Common` | Common | Common | L1540 | 20 | 跑分 · GeekBench 5 | `THERMAL_SPORTS_CG_POLICY`=1、`POWERHAL_SCREEN_OFF_STATE`=3、`SCHED_UCLAMP_MIN_TA`=50 |
| 13 | **8** | `com.primatelabs.geekbench6` | `Common` | Common | Common | L1568 | 22 | 跑分 · GeekBench 6 | `THERMAL_CGPPT_OPT_1`=118000、`THERMAL_CGPPT_OPT_2`=0、`POWERHAL_SCREEN_OFF_STATE`=3、`SCHED_UCLAMP_MIN_TA`=100、`GPU_BW_MODE_HINT`=1 |
| 14 | **8** | `com.primatelabs.geekbench7` | `Common` | Common | Common | L1598 | 20 | 跑分 · GeekBench 7 | `THERMAL_SPORTS_CG_POLICY`=1、`POWERHAL_SCREEN_OFF_STATE`=3、`SCHED_UCLAMP_MIN_TA`=50 |
| 15 | **9** | `net.kishonti.gfxbench` | `Common` | Common | Common | L1616 | 8 | 跑分 · GFXBench | `POWERHAL_SCREEN_OFF_STATE`=3 |
| 16 | **9** | `com.glbenchmark.glbenchmark27` | `Common` | Common | Common | L1632 | 8 | 跑分 · GLBenchmark 2.7 | `POWERHAL_SCREEN_OFF_STATE`=3 |
| 17 | **9** | `com.futuremark.dmandroid.application` | `Common` | Common | Common | L1658 | 10 | 跑分 · 3DMark | `THERMAL_CGPPT_OPT_1`=118000、`THERMAL_CGPPT_OPT_2`=0、`POWERHAL_SCREEN_OFF_STATE`=3 |
| 18 | **8** | `com.primatelabs.geekbench6.corporate` | `Common` | Common | Common | L1686 | 22 | 跑分 · GeekBench 6 企业版 | `THERMAL_CGPPT_OPT_1`=118000、`THERMAL_CGPPT_OPT_2`=0、`POWERHAL_SCREEN_OFF_STATE`=3、`SCHED_UCLAMP_MIN_TA`=100、`GPU_BW_MODE_HINT`=1 |
| 19 | **8** | `skynet.cputhrottlingtest` | `Common` | Common | Common | L1716 | 22 | 跑分 · CPU 降频测试 | `THERMAL_CGPPT_OPT_1`=118000、`THERMAL_CGPPT_OPT_2`=0、`POWERHAL_SCREEN_OFF_STATE`=3、`SCHED_UCLAMP_MIN_TA`=100、`GPU_BW_MODE_HINT`=1 |
| 20 | **8** | `com.ludashi.benchmark` | `Common` | Common | Common | L1748 | 18 | 跑分 · 鲁大师 | `THERMAL_SPORTS_CG_POLICY`=1、`POWERHAL_SCREEN_OFF_STATE`=3、`SCHED_UCLAMP_MIN_TA`=50、`IO_UFS_CLKSCALE`=2 |
| 21 | **8** | `com.ludashi.benchmark2` | `Common` | Common | Common | L1774 | 18 | 跑分 · 鲁大师（第二包名） | 同 #20，逐条相同 |
| 22 | **9** | `net.kishonti.gfxbench.v50006.corporate` | `Common` | Common | Common | L1830 | 8 | 跑分 · GFXBench 企业版 | `POWERHAL_SCREEN_OFF_STATE`=3 |
| 23 | **17** | `com.vivo.setupwizard` | `Common` | Common | Common | L2177 | **1** | 系统 · vivo 开机向导 | — （整块只有温控这一条） ⚠️ 见 §4 |
| 24 | **10** | `com.miHoYo.Nap` | `Common` | Common | Common | L2215 | 22 | 游戏 · 绝区零 | `FRS_TARGET_TEMP`=46000 |

> **"应用名"一列是常识判断，未在真机验证**（本子任务没跑 adb，无法 `pm list packages`
> 对照）。包名、Activity、FPS、WINDOW、行号都是 XML 原文，可直接复核。

### 1.1 索引分布（24 条）

| 索引 | 条数 | 占比 |
|---:|---:|---:|
| **8** | 12 | 50.0% |
| **9** | 5 | 20.8% |
| 0 | 2 | 8.3% |
| 3 / 5 / 10 / 12 / 17 | 各 1 | 各 4.2% |

**21 个不同包名**（`com.antutu.benchmark.full` 一个包占 4 条：`Common` + 3 个视频子 Activity）。

### 1.2 跑分全家桶（17 条，全在索引 8 / 9 上）

同一个包名下不同 Activity 的配置分别计为一条引用。

**索引 8（12 条）—— 配 `WINDOW=Single` 或 `Common` 都出现：**

| 包名 | 应用 | Activity 粒度 | WINDOW |
|---|---|---|---|
| `io.github.devriesl.raptormark` | RaptorMark | 整包 `Common` | Common |
| `com.antutu.ABenchMark` | 安兔兔实验室版 | 整包 `Common` | **Single** |
| `com.antutu.benchmark.full` | 安兔兔 | `Common`（总分项） | **Single** |
| `com.antutu.benchmark.full` | 安兔兔 | `...video.VideoCTSTestActivity` | Common |
| `com.antutu.benchmark.full` | 安兔兔 | `...video.VideoDecodeTestActivity` | Common |
| `com.antutu.benchmark.full` | 安兔兔 | `...video.VideoEditingActivity` | Common |
| `com.primatelabs.geekbench5` | GeekBench 5 | 整包 `Common` | Common |
| `com.primatelabs.geekbench6` | GeekBench 6 | 整包 `Common` | Common |
| `com.primatelabs.geekbench7` | GeekBench 7 | 整包 `Common` | Common |
| `com.primatelabs.geekbench6.corporate` | GeekBench 6 企业版 | 整包 `Common` | Common |
| `skynet.cputhrottlingtest` | CPU 降频测试 | 整包 `Common` | Common |
| `com.ludashi.benchmark` / `com.ludashi.benchmark2` | 鲁大师（两个包名） | 各整包 `Common` | Common |

**索引 9（5 条）—— 全部 `WINDOW=Common`：**

| 包名 | 应用 |
|---|---|
| `com.antutu.benchmark.full` | 安兔兔（`Common` Activity，`WINDOW=Single`） |
| `net.kishonti.gfxbench` | GFXBench |
| `net.kishonti.gfxbench.v50006.corporate` | GFXBench 企业版 |
| `com.glbenchmark.glbenchmark27` | GLBenchmark 2.7 |
| `com.futuremark.dmandroid.application` | 3DMark |

**归组是这么看的**（都是 XML 事实，不是推断）：

- **17 条索引 8/9 条目全部带 `PERF_RES_PEAK_POWER_BUDGET_MODE=1`**；
  索引 0/3/5/10/12/17 的 7 条一条都不带。
- 若再看"六件套"（`PEAK_POWER_BUDGET_MODE=1` + `SCHED_CORE_CTL_POLICY_ENABLE=0`
  + `CPUCORE_MIN_CLUSTER_0=4` + `CPUCORE_MIN_CLUSTER_1=3` + `CPUCORE_MIN_CLUSTER_2=1`
  + `POWER_MPMM_ENABLE=1`）：**索引 9 的 5 条全齐**，索引 8 的 12 条只有 6 条齐。
  齐的 6 条是 `com.antutu.ABenchMark`(L1435)、`geekbench5`(L1540)、`geekbench6`(L1568)、
  `geekbench7`(L1598)、`geekbench6.corporate`(L1686)、`skynet.cputhrottlingtest`(L1716)；
  不齐的 6 条是 `raptormark`(L1408)、`com.antutu.benchmark.full` 的 3 个视频子
  Activity（L1475/1495/1515）、`com.ludashi.benchmark` / `benchmark2`(L1748/1774)。

**为什么同一族跑分会拆成 8 / 9 两个索引 —— 未找到依据。**

### 1.3 非跑分的 7 条

| 索引 | 包名 | 说明 |
|---:|---|---|
| 12 | `com.android.camera` | 只绑 `CameraActivity` 一个 Activity，`<WINDOW>` 块里**只有温控这一条 data** |
| 3 | `com.miHoYo.enterprise.HK4E` | 原神国际服，`<WINDOW>` 块共 3 条 data |
| 5 | `com.kurogame.mingchao` | 鸣潮，块内 18 条 data |
| 10 | `com.miHoYo.Nap` | 绝区零，块内 22 条 data |
| 0 | `com.netease.moba.vivo` | 决战平安京 vivo 渠道，**挂在 `<FPS value="Common">` 下，没有 `<WINDOW>` 层**；该 FPS 块共 5 条 data |
| 0 | `com.netease.mrzh` | 明日之后，同样没有 `<WINDOW>` 层；该 FPS 块共 10 条 data，含 4 条 `FBT_LIMIT_*FREQ` 硬钳 |
| 17 | `com.vivo.setupwizard` | 开机向导，`<WINDOW>` 块只此一条 —— 见 §4 |

---

## 2. 来源 B：`powerscntbl.xml`（按 powerhint 场景）

**12 条全列。** 这张表是 `<scenario powerhint="N">…</scenario>` 结构，
`<data cmd="PERF_RES_THERMAL_POLICY" param1="N">` 在里面。
95 个 scenario 里只有 12 个带温控策略。

| powerhint ID | 场景注释（XML 原文） | 索引 | XML 行号 | 场景内其它 `data` 条数 | 有无 `CPUFREQ_MIN_CLUSTER_*` |
|---:|---|---:|---:|---:|:---:|
| **1150** | `performance vivo app lauch boost` | **7** | L238 | 20 | **有** 1800/2850/3250 |
| **2000** | `monter mode  boost`（Monster） | **10** | L581 | 7 | 无 |
| **2001** | `bench mark mode  boost` | **18** | L593 | 5 | **无** ⚠️ 见 §3 |
| **2002** | `superperformance mode boost` | **11** | L603 | 7 | 无 |
| **2003** | `perf mode  boost` | **0** | L615 | 5 | 无 |
| **2004** | `yuanshen perf mode  boost`（原神） | **5** | L625 | 6 | 无 |
| **2005** | `yuanshen monster mode  boost` | **5** | L636 | 6 | 无 |
| **2008** | `xingtie perf mode  boost`（星铁） | **5** | L647 | 8 | 无 |
| **2009** | `xingtie monster mode  boost` | **5** | L660 | 7 | 无 |
| **2010** | `ccc mode  boost` | **13** | L672 | 5 | 无 |
| **2150** | `balance mode vivo app lauch boost` | **7** | L682 | 23 | **有** 1400/2000/2200（另有 MAX 1800/2700/2900） |
| **2180** | **（无注释）** | **3** | L794 | **0** | 无 |

**两个读取时的坑：**

1. **`2180` 真的没有注释。** L794 紧跟在 L793 的 `</scenario>` 后面，前面没有任何
   `<!-- -->`。它**下面** L797 的 `<!-- benchmark mode vivo app lauch boost -->` 属于
   L798 的 `<scenario powerhint="3150">`，不要错配到 2180 头上
   （`parse_scn.py` 的注释归属逻辑是"注释配给下一个 scenario"，这里容易看反）。
   `2180` 也是 12 条里**唯一 `<data>` 只有温控一条**的场景 —— 场景体就三行：
   ```xml
   <scenario powerhint="2180">
       <data cmd="PERF_RES_THERMAL_POLICY" param1="3"></data>
   </scenario>
   ```
2. 注释原文有多处双空格（`monter mode  boost`、`bench mark mode  boost`、
   `perf mode  boost`），上表**按原文照抄**，没有规整化。
   `bench mark` 中间还带一个空格，而同表另外 3 个场景写的是 `benchmark`
   （3150 / 3160 / 3161）—— 这条对 §3 的猜想 3 有用。

### 2.1 与来源 A 的索引重合

| 索引 | 来源 A | 来源 B |
|---|---|---|
| 0 | 网易系 2 条 | 2003 perf mode |
| 3 | 原神国际服 | 2180 |
| 5 | 鸣潮 | 2004/2005/2008/2009（原神 / 星铁 perf+monster） |
| 7 | — | 1150/2150 vivo app launch |
| 10 | 绝区零 | 2000 Monster mode |
| 11 | 0 | 1 条（2002） | **1** |
| 12 | 1 条（相机） | 0 | **1** |
| 13 | 0 | 1 条（2010） | **1** |
| 18 | 0 | 1 条（2001） | **1** |
| 8 / 9 | 17 条（跑分） | — |

**只在来源 A 出现**：8、9、12、17。
**只在来源 B 出现**：7、11、13、18。

---

## 3. powerhint 2001 与策略 18

`powerscntbl.xml` L592–L601 把 `2001` 注释为 `bench mark mode boost`，
配置 `THERMAL_POLICY=18`、`SCREEN_OFF_STATE=1`，以及四条调度资源，共六条 data。
这个块没有 CPUFREQ_MIN 请求。

策略 18 的三簇频率表各只有一档：阈值 20°C、回滞 2°C，频率为
1800000 / 2100000 / 2100000 kHz，`mode=enabled`。
这是静态表值，不表示频率无条件钉住，也不能由“跑分”注释推断实时抬频效果。

HAL 的 SKIN 阈值还可能对应多份配置；13 与 18 的对应组相同。
判断是否实际选中 18，需结合请求、厂商状态和频率对照，不能单凭 SKIN 行。
此处保留选路差异；现有材料不足以解释厂商给 2001 配置这一组合的意图。

---

## 4. 索引 17 有引用，但没有对应文件

`power_app_cfg.xml` L2177 的 `com.vivo.setupwizard` 块引用 `THERMAL_POLICY=17`。
2026-10-05 核对的 21 份温控配置中没有 `thermal_policy_17.conf`；
13 个现有策略编号见 [配置索引](README.md)。

HAL 遇到这个缺失文件的处理方式、该引用是否实际执行，都没有运行时证据。
缺失本身不说明其他策略失效，也不构成恢复出厂或刷机测试的理由。

---

## 5. 复现本文所有数字

两个解析器在 `.local-baselines/policy-select/`，本文所有条数、行号都由它们跑出：

```bash
cd /d/CCode/MTK/.local-baselines/policy-select

# 来源 A：应为 "total PERF_RES_THERMAL_POLICY entries: 24"
python parse_appcfg.py

# 来源 B：12 行，hint / name / tp / comment 四列
python parse_scn.py /d/CCode/MTK/.local-baselines/pull/powerscntbl.xml
```

自己再核一遍不依赖这两个脚本：

```bash
# 来源 A 的条数（应为 24）
grep -c 'PERF_RES_THERMAL_POLICY' /d/CCode/MTK/.local-baselines/policy-select/power_app_cfg.xml

# 来源 B 的条数（应为 12）
grep -c 'PERF_RES_THERMAL_POLICY' /d/CCode/MTK/.local-baselines/pull/powerscntbl.xml

# 场景总数（应为 95）
grep -c '<scenario ' /d/CCode/MTK/.local-baselines/pull/powerscntbl.xml
```

**注意**：`parse_scn.py` 把注释配给"下一个" `<scenario>`。读 `2180`（无注释）时
它打印 `comment=None`，而 L797 那条 `benchmark mode vivo app lauch boost`
会被配给它后面的 3150 —— 符合 XML 里注释前置的写法，是对的。
§2 的坑 1 说的就是这里。

---

## 6. 相关文档

- [README.md](README.md) —— 本目录索引、21 份明文清单、`02==12` / `08==09` 重复说明
- [field-reference.md](field-reference.md) —— 每个字段的含义、单位、出现位置
- [当前温控事实](../facts.md) —— 运行时证据与结论边界
- [历史调查](../../archive/cpu/frequency-ceiling-investigation-through-2026-09-26.md) —— 选路与解码调查过程


