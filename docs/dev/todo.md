# 待办

只列仍决定推进的工作。完成、取消或被替代时删除，结论写回相关说明。

## 温控档位：三条写路径已收敛成一个控制器

同一个 PowerHAL 资源（`PERF_RES_THERMAL_POLICY`）原先有**三条互不知情的写路径**：

| 入口 | 怎么写的 |
|---|---|
| CPU 页「应用」/ 切方案 | 跟随频率一起下发，长期保持，写进方案 |
| 按应用温控 | 前台命中规则就下发，离开就放 |
| 实验室 | 即时下发，20 秒后自行失效，可长期保持、可自动扫档 |

三处各自 `PowerHal.acquire`、各自持句柄，释放时只放自己那一把。用户完全可能同时被
两边持有 —— 现象是"我明明设了却没用 / 设完又弹回去"，而三处界面各自显示正常，
谁也不报错。PowerHAL 对同一资源的多个请求怎么合并没有文档，所以唯一的稳妥答案是
**一次只有一把句柄**。

### 已建：`ThermalPolicyController`

`data/thermal/ThermalPolicyController.kt`，全项目唯一写入口。规则：

- 谁最后要谁拿到；放手时回到**常驻意图** `standingIndex = userIndex ?: appRuleIndex`
  （用户选的那一档优先于按应用规则 —— 那是用户刚做的决定，不该被轮询覆盖）
- 先拿新句柄再放旧的：反过来有一个"两把都没有"的窗口，设备回到系统默认温度阶梯，
  频率被压一下再放开，用户看到的是"切档时卡了一下"
- **相同档位也要换句柄**，不能只改来源标签：临时档到期是固件行为、我们收不到通知，
  只改标签会让用户那一档跟着没
- 实验室的临时档到期后由调用方 `releaseLab()`，控制器负责把常驻意图放回去 ——
  否则一次实验会把用户设好的档位吃掉

`state: StateFlow<State>` 是全局一份，三个界面读同一个流，所以"CPU 页改了实验室立刻
看见"不需要任何同步代码。

底层没有重复：`ThermalPolicyCatalog.indices()` 改为委托
`ThermalPolicyReader.availableIndices()`（同一份列目录、同一套失败语义 ——
原来 Catalog 用 `list` 把"没列成"吞成空列表，Reader 用 `listOrNull` 区分两者）。
资源号收敛到 `THERMAL_POLICY_RESOURCE` 一处，原来那个各自为政的对象已删除。

### 顺带修掉一个真 bug

`CpuViewModel` 里切换方案时下发档位那一处：
`profile.thermalIndex` 为 null 时原实现走 `disable()`，**把用户的档位关掉了**，
而它上面三行的注释写的正是"null 表示这个方案不管档位，保持现场不动"。
代码和自己的注释正相反。现在两处都改成 `?.let { … }`，null 就是不动。

### 仍待定：实验室那一项的去留

它现在是重复入口，但也是唯一能做自动扫档的地方。"这一档干什么"已经能解开
（13 份 conf 全解出来了），实验室原有"语义没有公开文档、请自己试"的 framing 已过期。
若采纳退役，按退役流程处理请求释放与持久状态后再删；写入路径已经统一，退役只剩 UI。

## VIVO 触控后端的支持判断

旧测试中事务码 16/17 返回成功，但所采样读数未发生对应变化；
这不足以证明所有场景无效，也不足以向用户承诺效果。当前后端选择以可读能力作门槛，
需要让支持判断和结果表达与证据一致。

完成判据：确认受支持的控制语义并验证相关效果，或收敛该入口；
若退役，处理请求释放和持久恢复状态后再移除实现。
依据及测试边界见 [触控命令](../device/commands.md#触控)；
实施前检查 `VivoTouchSession` 与 `TweakRepository.setTouchBackend` 的当前代码。

## 默认设置与配色（2026-10-10 已做）

新装 App 的默认值改了，改的是**没写过值的用户**（已选过的保留原选择）：

| 设置 | 新默认 |
|---|---|
| 底栏滚动行为 | 上滑隐藏 |
| CPU 页控制区 | 合并卡片 |
| 主题色 | 莫兰迪鼠尾草（另有「自定义」档：色相 + 浓度两个滑杆） |
| 切换底栏标签时 | 回到上次的页面 |

莫兰迪色板从 6 个扩到 **14 个**。挑色时用 WCAG 相对亮度核过，全部落在既有区间
（约 0.22–0.27）内 —— 原因是 `onPrimary` 固定取白，accent 一亮白字就糊。
**加新色前先算亮度，别凭眼睛。** 界面上这一组也改成了色板网格（`MorandiSwatchGrid`），
十几个低饱和度色块并排时，逐个带文字的列表没法比色。

## 界面规范补了两条（2026-10-10）

「日志占用 X」贴边那个 bug，**规范和代码两边都有责任**，都已补：

- `ui.md` 新增 **卡片内的文字与间距**：标题组件只用于标题；会随设备状态变的
  （占用、读数、版本号）就不是标题。判据写成可检查的 —— 看它会不会变。
- `ui.md` 新增 **按钮与操作**：多个按钮不强制 `weight(1f)` 等分（改 `FlowRow`，
  文字不拆）；不用缩小字号避免折行；一屏一个实心按钮。
- `architecture.md` 新增 **状态读取的位置决定重组范围**：每帧变的状态（Pager 位移、
  动画进度）必须用 lambda 传进最小的 composable，配 `Modifier.offset { }`。

## 报错分层架构：`state.message` 全量改造（未做完）

`Failure`（`data/Failure.kt`）+ `FailureNote`（`ui/components/FailureNote.kt`）已建好，
规范写在 [wording.md](wording.md#报错分两层不许合成一句)。

**已全部接上**（2026-10-10，一次构建推完，摘要文字一字未改）：

- 四个 `Outcome` 都加了 `detail: String?`
- 五个状态类的 `message: String?` → `message: Failure?`
  （`CpuUiState`、`GpuUiState`、`HomeUiState`、`TweaksUiState`，以及 `ExportViewModel` 的内部状态）
- 屏幕改用 `FailureNote`：`CpuScreen`、`HomeScreen`、`GpuScreen`、`ExportScreen`、
  `SmartPlatformScreen`、`TweaksScreen`、`ThermalCard`
- 捕获异常处用 `Failure.from(tag, summary, e)` —— 构造即落日志

**为什么能一次推完**：摘要是同一个 `Message`，文字不变；只是多了可选的「显示更多」。
没有新措辞要核对，所以不需要一屏一次构建。

**剩下没接的**（2026-10-11 起只剩后三项）：

| 状态 | 字段 |
|---|---|
| **已做** | `SettingsUiState.backupMessage` / `touchBackendMessage` → `Failure?`，界面走 `FailureNote` |
| 跳过范围内 | `LabUiState.message`、`FeatureTrial`、`AppThermal` 的 message |

那两项已做的一起收了源：`BackupRepository.export/apply` 不再返回拼好的中文字符串，
改返回 `Failure`（摘要走资源、原文进 `detail`）；`import_read_failed` 与
`report_export_failed` 去掉了 `%1$s`，原文改走 `detail`。

## 界面文案存量迁移

按 [wording.md](wording.md) 逐处把中文迁进 `strings.xml`；判据与分层见
[维护与验证](wording.md#维护与验证)。**手工迁**，迁完一个文件编译一次。

量具是 `python tools/check_strings.py`，已挂在 `preBuild` 上，每次构建都会跑。

**范围**：跳过实验室与测试中心相关页面（那些功能可能整份退役，先迁是白做）——
`data/lab/**`、`feature/lab/**`、`feature/cpu/CpuTestsScreen.kt`、
`feature/tweaks/FeatureTrial*`、`feature/tweaks/TweakControls.kt`、
`feature/tweaks/TuningScreen.kt`、`feature/thermal/AppThermal*`。

当前（2026-10-10）`domain/` 0 处；`data/` 非日志 403 处；`feature/` 与 `ui/` 217 处；
工程日志与报告原文 173 处不计入。基线已随进度重冻。

**保留页面已清零。** `feature/` 与 `ui/` 剩下的 217 处全在跳过的范围内：
实验室与测试中心 189 处，加 `CpuViewModel` 里 28 处测试中心的探测流程
（实现文件导出、ROM 只读快照、相机对照、多阶段负载测试）—— 它们由 `CpuTestsScreen`
驱动，功能本身可能整份退役。

`data/` 那 403 处的构成：实验室定义 156、`PrivilegeManager` 37、`VivoTweaks` 20、
`CapabilityProbe` 19、`LabController` 19、`BatteryReportParser` 14、`CpuControl` 13、
`CpuProbeRequest` 13，其余散在 `AxMaintenance`、`CompileStatus`、`SmartBackup`、
`RomConfigTransfer`、`DiagReport`、`BackupRepository`、`GameBoost`、`PhaseOffset` 等文件里，
多是诊断原文与报告字段 —— 按 [wording.md](wording.md)「报告原始字段和工程日志按数据处理」
多数本就该留在代码里，逐条复核即可。

已迁完：`SettingsScreen`、`CpuScreen`、`CpuAdvancedScreen`、`HomeScreen`、`AboutCard`、
`LogScreen`、`ThermalPolicySheet`、`MainActivity`、`DiagScreen`、`NotificationScreen`、
`SubScreenTopBar`、`FrequencyChart`、`ExpandableDetails`、`FeatureInfo`、`GpuScreen`、
`TweaksScreen`、`SmartPlatformScreen`、`StatusToolsScreen`、`AppNavHost`，
以及全部选项枚举和 `domain` 里的中文（改由 `ui/OptionLabels.kt` 映射）。

迁移时踩到过、值得先看的两条：

- **资源只能在 composable 里取。** `LazyColumn` 的 content lambda、`onClick` /
  `onCheckedChange` 这类回调都不是 composable 上下文 —— 要把字符串先取好再传进去。
- **`@Composable` 不能出现在短路链里。** `a?.text() ?: b`、`if (x) "…" else "…"`
  里调 `stringResource` 会直接编译失败；拆成显式分支。

顺带清掉的死代码：`GpuTuner.Outcome.handler`（无人读）、`ClusterSeries.label`（无人读）、
`SystemShortcuts.pages()`/`openPage()`/`dialCode()`（无人调用，整个文件只剩 `DARK_CODES`）、
`TweakCommands.companionLabel`（与枚举自己的 `labelRes` 重复，且它造的"方案 N"
是枚举序号，不是任何意义上的方案编号）。

### 数据层的类型改造

**剩下的主要不是"搬字符串"，是让控制层不再把中文当数据传上来。**
已定的写法是 [Message](../../mtk-optimizer/app/src/main/java/magicau/mtkcontroller/data/Message.kt)：
数据层给「一条资源 + 参数」，展示边界用 `Message.text()`（Compose）或
`resolve(context)`（其他展示边界与日志）解析。嵌套参数会递归解析，
所以 `"无法释放上一次请求:${released.message}"` 这种拼接也能拆成模板。

| 要改的 | 状态 |
|---|---|
链是自上而下的，一层套一层：
`PowerHal.Result` → `PerfLockController.Outcome` → `CpuControl.Outcome` → 界面。

| 要改的 | 状态 |
|---|---|
| `CapabilityReport.Check.label`、`CheckCategory.title` | **已做** |
| `GpuTuner.Outcome.message`（13 条） | **已做** |
| `TweakRepository.Outcome.message`（16 条） | **已做**，含 `VivoTweaks` 25、`GameBoost` 12、`FeatureTrial` 1 的生产者 |
| `PowerHal.Result.message`（8 条） | **已做** |
| `PerfLockController.Outcome.message`（5 条） | **已做** |
| `CpuControl.Outcome.message`（20 条）+ 读回判定与调速器提示 | **已做**，`CpuControl` 因此拿到 `Context`（逐簇拼接串没法只靠返回 id 表达） |
| `AxSessionState.message`、`AxTaskState.message` | **已做**；服务是通知的展示边界，直接用 `getString` |
| `PhaseOffset.Key.label` | **已做** |
| `CpuLimitReading.conflictReason` | **已做** |

`AxTaskState` 顺带把「OK:/ERROR:」前缀的判定从界面挪回服务：界面原先靠
`message.startsWith("ERROR:")` 决定标题，那是在把协议标识当界面判据。现在是
`ok: Boolean?`。

已建的两处产物：`data/Message.kt`（类型与 `resolve`）、`ui/MessageText.kt`（Compose 解析）。
消费端一律在展示边界解析：Compose 用 `text()`，ViewModel 用 `resolve(container.context)`，
日志与导出报告用 `resolve(context)` —— 界面状态仍是 `String`，所以 UI 代码不用动。

已记录的一处**行为变化**：`setFreqSchedCompanion` 遇到认不出的配合项 id 时，
原先会拼出「已开启null」，现在报「已关闭频率配合项」。旧的 id 理论上只来自被移除的配合项。

完成判据：`domain/` 为 0；保留页面里不再有用户可见的中文写在代码里；
设备原文、协议标识、构建标识、纯占位符号（`—`）和数字格式化仍按数据留在代码里。
