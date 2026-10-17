package magicau.mtkcontroller.feature.lab

import kotlinx.coroutines.CancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.lab.LabPlacement
import magicau.mtkcontroller.data.lab.LabCatalog
import magicau.mtkcontroller.data.lab.LabCategory
import magicau.mtkcontroller.data.lab.LabCores
import magicau.mtkcontroller.data.lab.LabController
import magicau.mtkcontroller.data.lab.ThermalPolicyCatalog
import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.thermal.ThermalPolicyReader
import magicau.mtkcontroller.data.thermal.ThermalPolicyController
import magicau.mtkcontroller.data.thermal.ThermalPolicySummary
import magicau.mtkcontroller.data.lab.LabChoice
import magicau.mtkcontroller.data.lab.LabFeature
import magicau.mtkcontroller.data.lab.THERMAL_POLICY_KEY
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.CpuCluster

/** One editable feature, plus everything known about how this device responds. */
data class LabItemState(
    val feature: LabFeature,
    /**
     * 未判定，或节点存在但读不到 —— 控件保持可点。
     *
     * **没有回读不等于不能下发。** SELinux 和 cpufreq 的权限位让大量能用的节点
     * 读不到，而它们背后的 PowerHAL opcode 照样响应。只有 `false` 才置灰。
     */
    val supported: Boolean? = null,
    val nodeValue: String? = null,
    val verdict: LabController.Verdict? = null,
    /** policy name (or [GLOBAL]) -> value. */
    val values: Map<String, Int> = emptyMap(),
    val hold: Boolean = false,
    val busy: Boolean = false,
    /**
     * 运行时解析出的选项，来自 [LabFeature.dynamicChoices]。
     * 空 = 这个功能用静态选项，或还没解析出来。
     */
    val choices: List<LabChoice> = emptyList(),
    /** 运行时读数，null = 这个功能没有读数，或还没读到。 */
    val readout: String? = null,
    /** 各档位的中文摘要（只有温度和频率），key = 档位编号。 */
    val briefs: Map<Int, String> = emptyMap(),
) {
    /** 当前选中的那一档的摘要，按 [values] 现取 —— 换档立刻跟着变。 */
    val selectedBrief: String?
        get() = if (feature.key == THERMAL_POLICY_KEY) {
            values.values.firstOrNull()?.let { briefs[it] }
        } else {
            null
        }
}

data class LabUiState(
    val loading: Boolean = true,
    val powerHalReady: Boolean = false,
    val clusters: List<CpuCluster> = emptyList(),
    /**
     * 按**归属**（[LabPlacement]）分组，组内按技术分类（[LabCategory]）排序。
     *
     * 分组键从 category 换成 placement，是因为用户要先分清"哪些是正式功能、
     * 哪些只是探测、哪些是开发工具" —— 那是使用决策；而"这块归 CPU 还是调度"
     * 是实现细节，排在第二级就够了。
     */
    val groups: List<Pair<LabPlacement, List<LabItemState>>> = emptyList(),
    /**
     * 一次性结论（释放结果一类）。
     *
     * 用 [Failure] 而不是裸 `String`：**摘要给人看、原话收起可复制**。
     * 原来这里是 `String`，`LabController.reset()` 直接 `resolve(context)` 把中文
     * 焊死返回，调用方连成功失败都分不出来，点"显示更多"也没有东西可看。
     */
    val message: Failure? = null,
    /**
     * 固件当前生效的温控档位，以及它是谁要求的。
     *
     * 来自 [ThermalPolicyController] 的共享状态，不是实验室自己读的 —— 温控档位
     * 有三个来源（CPU 页、按应用温控、实验室），只有一处真源，三处界面才不会
     * 各显示各的。
     */
    val thermalAppliedIndex: Int? = null,
    val thermalAppliedSource: ThermalPolicyController.Source? = null,
    /**
     * 编辑模式：行上显示"能用 / 没用"的标记按钮。
     *
     * 平时不显示 —— 用户大部分时间是来**用**这些东西的，不是来打分的。
     * 标记是偶尔做一次的事，藏在编辑模式里，评分按钮就不会天天占着屏幕。
     */
    val editing: Boolean = false,
)


/**
 * Backs the canary lab.
 *
 * The screen has two halves with different risk profiles, and they are kept
 * apart deliberately: the read-only panel never writes, and the canary list only
 * writes when the user moves a control on a card that says what it will do.
 */
/** 温控策略索引这个功能的 key。只有它需要"每档的中文摘要"。 */


class LabViewModel(private val container: AppContainer) : ViewModel() {


    companion object {
        /** Key used for features that are not per-cluster. */
        const val GLOBAL = ""
    }

    private val _state = MutableStateFlow(LabUiState())
    val state: StateFlow<LabUiState> = _state.asStateFlow()

    private var clusters: List<CpuCluster> = emptyList()

    init {
        refresh()
        // 收共享的温控档位状态。**不自己读节点**：那一档有三个来源，
        // 各自读会各显示各的，而用户只看到一个"当前生效"。
        // CPU 页改了这里立刻跟着变，不需要任何同步代码。
        viewModelScope.launch {
            ThermalPolicyController.state.collect { thermal ->
                _state.value = _state.value.copy(
                    thermalAppliedIndex = thermal.appliedIndex,
                    thermalAppliedSource = thermal.appliedSource,
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)

            clusters = withContext(Dispatchers.IO) {
                runCatching { container.cpuScanner.scan(deep = false) }.getOrDefault(emptyList())
            }
            val powerHalReady = PowerHal.isAvailableNow()
            val verdicts = container.labController.verdicts()

            // One batched read for every declared node, so the UI can grey out
            // what this device lacks instead of offering a control that cannot
            // work — without paying a shell spawn per feature.
            val nodes = LabCatalog.features
                .mapNotNull { displayNode(it, clusters) }
                .distinct()
            val values = Sysfs.readMany(nodes)

            // A node we cannot read is not a node the device lacks. Only a
            // plain "no such file" disables a control; SELinux and the
            // cpufreq permission bits make plenty of working nodes unreadable,
            // and the PowerHAL opcode behind them answers regardless.
            val states = Sysfs.states(nodes.filter { values[it] == null })

            // 选项来自设备本身的功能（温控策略索引）要列目录才知道有哪些。
            val dynamic = mutableMapOf<String, List<LabChoice>>()
            for (feature in LabCatalog.features) {
                val provider = feature.dynamicChoices ?: continue
                dynamic[feature.key] = provider()
            }
            // 读数逐个取。都在 IO 上，且只有声明了 readout 的功能才会有。
            val readouts = mutableMapOf<String, String>()
            for (feature in LabCatalog.features) {
                val reader = feature.readout ?: continue
                readouts[feature.key] = reader() ?: continue
            }
            // 温控策略索引各档的中文摘要：现读现解，不预写任何档位的含义。
            val briefs = mutableMapOf<Int, String>()
            runCatching {
                for (index in ThermalPolicyReader.availableIndices()) {
                    val policy = ThermalPolicyReader.read(index)
                    briefs[index] = ThermalPolicySummary.brief(policy)
                }
            }

            val items = LabCatalog.features.map { feature ->
                val node = displayNode(feature, clusters)
                val supported = when {
                    node == null -> null
                    values[node] != null -> true
                    states[node] == Sysfs.NodeState.ABSENT -> false
                    else -> null
                }
                LabItemState(
                    feature = feature,
                    supported = supported,
                    nodeValue = node,
                    verdict = verdicts[feature.key],
                    values = defaultValues(feature, dynamic[feature.key]),
                    choices = dynamic[feature.key] ?: emptyList(),
                    readout = readouts[feature.key],
                    briefs = if (feature.key == THERMAL_POLICY_KEY) briefs else emptyMap(),
                )
            }

            _state.value = LabUiState(
                loading = false,
                powerHalReady = powerHalReady,
                clusters = clusters,
                // 一级按归属，二级按技术分类。compareBy 用两个 order 字段，
                // 不要在组内再分组 —— 那会把一屏又切碎。
                // 标为「没用」的直接不显示 —— **除非在编辑模式**（不然用户没法取消标记）。
                // 这是 iOS 那种"清理掉用不上的东西"的做法：列表越用越干净，
                // 剩下的都是这台机器上真能用的。
                groups = items
                    .filter { _state.value.editing || it.verdict?.stateEnum() != LabController.State.MANUAL_UNUSABLE }
                    .groupBy { it.feature.placement }
                    .toSortedMap(compareBy { it.order })
                    .map { (placement, list) ->
                        placement to list.sortedWith(
                            // 只有分类一个键。以前第二个键是 label，那是中文串；
                            // 现在它是资源 id，排序会变成按 id 数字排（毫无意义）。
                            // 去掉即可 —— sortedWith 是稳定排序，同类保持声明顺序。
                            compareBy({ it.feature.category.order }),
                        )
                    },
            )
        }
    }

    /**
     * Representative node for a feature's capability check.
     *
     * A per-cluster template is resolved against the first cluster: existence is
     * a property of the platform, not of one policy.
     */
    private fun displayNode(feature: LabFeature, clusters: List<CpuCluster>): String? {
        val template = feature.node ?: return null
        return if (template.contains("%s")) {
            clusters.firstOrNull()?.let { template.replace("%s", it.policy) }
        } else {
            template
        }
    }

    private fun defaultValues(
        feature: LabFeature,
        choices: List<LabChoice>? = null,
    ): Map<String, Int> {
        // 运行时选项交给 catalogue 自己决定首选，代码里不写"哪一档好" ——
        // 那是实测结论，ROM 升级就可能失效。
        val initial = if (choices != null && choices.isNotEmpty()) {
            ThermalPolicyCatalog.initialIndex(choices.map { it.value })
        } else {
            feature.range.first
        }
        return when {
            feature.perCore -> LabCores.keys.associateWith { initial }
            feature.perCluster -> clusters.associate { it.policy to initial }
            else -> mapOf(GLOBAL to initial)
        }
    }

    fun setValue(feature: LabFeature, policy: String, value: Int) {
        updateItem(feature.key) { item ->
            // 运行时选项（温控策略索引）不在静态 range 里，coerceIn 会把它们全压成同一个值。
            val allowed = if (item.choices.isNotEmpty()) {
                item.choices.any { it.value == value }
            } else {
                value in feature.range
            }
            val next = if (allowed) value else item.values[policy] ?: value
            item.copy(values = item.values + (policy to next))
        }
    }

    fun setHold(feature: LabFeature, hold: Boolean) {
        updateItem(feature.key) { it.copy(hold = hold) }
    }

    /** Send the current values and record what the device did. */
    fun test(feature: LabFeature) {
        val item = currentItem(feature.key) ?: return
        if (item.supported == false) return

        viewModelScope.launch {
            updateItem(feature.key) { it.copy(busy = true) }
            val verdict = container.labController.apply(
                feature = feature,
                clusters = clusters,
                values = item.values,
                hold = item.hold,
            )
            updateItem(feature.key) {
                it.copy(busy = false, verdict = verdict)
            }
            _state.value = _state.value.copy(message = null)
        }
    }

    fun setEditing(on: Boolean) {
        _state.value = _state.value.copy(editing = on)
    }

    /**
     * 手动标一条：能用 / 没用 / 清除。
     *
     * 传 null 表示清除标记，回到"未测试"。
     */
    /**
     * 编辑期间改本地草稿，**不落盘、不重扫**。
     *
     * 这里原来调 `refresh()`，而 `refresh` 会置 `loading = true` 把整页换成转圈 ——
     * 每点一次标记就闪一下、等半秒，连续标几项根本没法用。
     * 标记只是改本地一个字段，和设备无关，不该触发重新扫描。
     */
    fun mark(feature: LabFeature, state: LabController.State?) {
        pending[feature.key] = state
        updateItem(feature.key) { it.copy(verdict = state?.let { s -> LabController.Verdict(s.name, "") }) }
    }

    /** 编辑过程中改过的项，等「完成」时一次性落盘。 */
    private val pending = mutableMapOf<String, LabController.State?>()

    /**
     * 左上角的「×」或返回：放弃本次编辑，把草稿丢掉。
     *
     * 草稿只活在内存里，丢掉不会影响已保存的标记。
     */
    fun discardEditing() {
        pending.clear()
        _state.value = _state.value.copy(editing = false)
        refresh()
    }

    /** 右上角的「√」：把这次改过的项一次性写进去。 */
    fun commitEditing() {
        viewModelScope.launch {
            pending.forEach { (key, state) -> container.labController.markManually(key, state) }
            pending.clear()
            _state.value = _state.value.copy(editing = false)
            refresh()
        }
    }

    /** Release everything the lab holds and re-read the panel. */
    fun resetAll() {
        viewModelScope.launch {
            val outcome = container.labController.reset()
            _state.value = _state.value.copy(message = Failure(outcome.message, outcome.detail))
        }
    }

    fun clearObservations() {
        viewModelScope.launch {
            container.labController.clearVerdicts()
            refresh()
        }
    }

    private fun currentItem(key: String): LabItemState? =
        _state.value.groups.flatMap { it.second }.firstOrNull { it.feature.key == key }

    private fun updateItem(key: String, transform: (LabItemState) -> LabItemState) {
        _state.value = _state.value.copy(
            groups = _state.value.groups.map { (placement, items) ->
                placement to items.map { if (it.feature.key == key) transform(it) else it }
            },
        )
    }

    /**
     * 离开页面就放掉本页持有的请求。
     *
     * **为什么必须有这个**：实验室的请求是 daemon UserService 发的、句柄存在
     * DataStore 里，离开页面不会自动消失。原来三个 ViewModel 一个 onCleared
     * 都没有，于是"长期保持"的请求在用户离开后继续压着频率，而两边界面都不报错 ——
     * 正是"我设了却没用 / 设完又弹回去"那类故障的成因。
     *
     * `reset()` 同时放 lock 和温控档位（[LabController.reset]），
     * 与 CPU 页「释放」撤的是同一套东西。
     */
    override fun onCleared() {
        // onCleared 不在协程里，而 reset() 要跑 binder。用 applicationScope 之类
        // 长寿命作用域会越过 ViewModel 寿命，所以这里起一个一次性的：
        // ViewModel 已死，但释放必须跑完。
        viewModelScope.launch {
            runCatching { container.labController.reset() }
                .onFailure { if (it is CancellationException) throw it }
        }
        super.onCleared()
    }
}


private const val TAG = "Lab"

