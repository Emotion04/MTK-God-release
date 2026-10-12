package magicau.mtkcontroller.feature.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.lab.LabCatalog
import magicau.mtkcontroller.data.lab.LabCategory
import magicau.mtkcontroller.data.lab.LabCores
import magicau.mtkcontroller.data.lab.LabController
import magicau.mtkcontroller.data.lab.ThermalPolicyCatalog
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
    /** 自动扫档进行到哪儿，null = 没在扫。 */
    val sweepProgress: String? = null,
    /** 自动扫档的结果，一档一行。 */
    val sweepResults: List<SweepRow> = emptyList(),
    /**
     * 运行时解析出的选项，来自 [LabFeature.dynamicChoices]。
     * 空 = 这个功能用静态选项，或还没解析出来。
     */
    val choices: List<LabChoice> = emptyList(),
    /** 运行时读数，null = 这个功能没有读数，或还没读到。 */
    val readout: String? = null,
    /** 各档位的中文摘要（只有温度和频率），key = 档位编号。 */
    val briefs: Map<Int, String> = emptyMap(),
    /** 当前选中的那一档的摘要，直接显示在卡片上。 */
    val selectedBrief: String? = null,
)

/**
 * 自动扫档里一个索引的实测结果。
 *
 * 采的是**上限节点本身**，不是当前频率 —— 上限不需要负载就会变，
 * 而频率没有负载时只会停在下限，看了等于没看。
 */
data class SweepRow(
    val value: Int,
    /** true = 扫档前的无请求基线，不是某一档的结果。 */
    val isBaseline: Boolean = false,
    val capMin: Int?,
    val capMax: Int?,
    val curMin: Int?,
    val curMax: Int?,
    val samples: Int,
) {
    val capText: String
        get() = capMin?.let { lo -> capMax?.let { hi -> if (lo == hi) "$lo" else "$lo–$hi" } } ?: "读不到"

    val curText: String
        get() = curMin?.let { lo -> curMax?.let { hi -> if (lo == hi) "$lo" else "$lo–$hi" } } ?: "读不到"
}

data class LabUiState(
    val loading: Boolean = true,
    val powerHalReady: Boolean = false,
    val clusters: List<CpuCluster> = emptyList(),
    val groups: List<Pair<LabCategory, List<LabItemState>>> = emptyList(),
    val message: String? = null,
    /**
     * 固件当前生效的温控档位，以及它是谁要求的。
     *
     * 来自 [ThermalPolicyController] 的共享状态，不是实验室自己读的 —— 温控档位
     * 有三个来源（CPU 页、按应用温控、实验室），只有一处真源，三处界面才不会
     * 各显示各的。
     */
    val thermalAppliedIndex: Int? = null,
    val thermalAppliedSource: ThermalPolicyController.Source? = null,
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

    private var sweepJob: Job? = null

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
                    selectedBrief = if (feature.key == THERMAL_POLICY_KEY) {
                        val chosen = defaultValues(feature, dynamic[feature.key]).values.firstOrNull()
                        chosen?.let { briefs[it] }
                    } else {
                        null
                    },
                )
            }

            _state.value = LabUiState(
                loading = false,
                powerHalReady = powerHalReady,
                clusters = clusters,
                groups = items.groupBy { it.feature.category }
                    .toSortedMap(compareBy { it.order })
                    .map { (category, list) -> category to list },
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

    /**
     * 把 [LabFeature.sweepValues] 一个一个自动发下去，每档采一次上限节点。
     *
     * 判据是 `scaling_max_freq`：温控策略换了，上限节点立刻变，不需要负载。
     * 每档之间先释放再发，否则两把锁叠在一起，看不出是哪一档起的作用。
     */
    fun sweep(feature: LabFeature, stopOnHit: Boolean = true) {
        val values = feature.sweepValues ?: return
        if (sweepJob?.isActive == true) return
        sweepJob = viewModelScope.launch {
            // 取最大的那一簇：温控压的就是大核。
            val cluster = clusters.maxByOrNull { c ->
                c.policy.removePrefix("policy").toIntOrNull() ?: -1
            }
            val dir = cluster?.let { "/sys/devices/system/cpu/cpufreq/${it.policy}" }
            val capNode = dir?.plus("/scaling_max_freq")
            val curNode = dir?.plus("/scaling_cur_freq")
            val rows = mutableListOf<SweepRow>()
            var hit = -1

            // 先取一个无请求基线。温控噪声自己就能在 1800000–2600000 之间跳，
            // 拿绝对数当阈值必然误判 —— 只有高于这个基线的峰值才算信号。
            updateItem(feature.key) { item ->
                item.copy(
                    sweepProgress = "正在测基线（${SWEEP_HOLD_MS / 1000} 秒）…",
                    sweepResults = emptyList(),
                )
            }
            val baseline = sample(capNode, curNode, 0).copy(isBaseline = true)
            rows += baseline
            AppLog.i(TAG, "[扫] 基线 · cap ${baseline.capText} · cur ${baseline.curText}")

            for ((index, value) in values.withIndex()) {
                updateItem(feature.key) { item ->
                    item.copy(
                        values = item.values + ("" to value),
                        sweepProgress = "第 ${index + 1}/${values.size} 档，索引 $value",
                        sweepResults = rows.toList(),
                    )
                }
                container.labController.releaseRequests()
                container.labController.apply(
                    feature = feature,
                    clusters = clusters,
                    values = mapOf("" to value),
                    hold = false,
                    durationMs = SWEEP_HOLD_MS,
                )

                val row = sample(capNode, curNode, value) { live ->
                    updateItem(feature.key) { item ->
                        item.copy(
                            sweepProgress = "第 ${index + 1}/${values.size} 档，索引 $value，cap $live",
                        )
                    }
                }
                rows += row
                AppLog.i(TAG, "[扫] 索引 $value · cap ${row.capText} · cur ${row.curText} · ${row.samples} 点")
                updateItem(feature.key) { it.copy(sweepResults = rows.toList(), sweepProgress = null) }

                // 整档都高于基线的峰值才算命中：温控噪声自己就能跳到 2600000，
                // 只有"比不请求时最好的情况还高"才可能是策略的作用。
                val base = baseline.capMax
                if (stopOnHit && base != null && row.capMin != null && row.capMin > base) {
                    hit = value
                    AppLog.i(
                        TAG,
                        "[扫] 索引 $value 整档 cap ${row.capText} 都高于基线峰值 $base，提前收工",
                    )
                    break
                }
            }

            container.labController.releaseRequests()
            updateItem(feature.key) { it.copy(sweepProgress = null) }
            _state.value = _state.value.copy(
                message = if (hit >= 0) "索引 $hit 把上限抬上去了" else "扫完了，没有索引抬动上限",
            )
        }
    }

    /** 采样串的人类可读形式，扫档过程中实时显示。 */
    private fun List<Int>.capText(): String =
        if (isEmpty()) "读不到" else if (min() == max()) "${max()}" else "${min()}–${max()}"

    /**
     * 采 [SWEEP_HOLD_MS] 毫秒的上限与当前频率。
     *
     * 采上限节点而不是当前频率：上限不需要负载就会变，而频率没有负载时只会
     * 停在下限，看了等于没看。
     */
    private suspend fun sample(
        capNode: String?,
        curNode: String?,
        value: Int,
        onLive: (String) -> Unit = {},
    ): SweepRow {
        val caps = mutableListOf<Int>()
        val curs = mutableListOf<Int>()
        val deadline = System.currentTimeMillis() + SWEEP_HOLD_MS
        // 协协取消靠 delay() 抛 CancellationException，不需要在这里查 isActive ——
        // suspend 函数里也没有 CoroutineScope 接收者可用。
        while (System.currentTimeMillis() < deadline) {
            capNode?.let { Sysfs.read(it)?.trim()?.toIntOrNull()?.let(caps::add) }
            curNode?.let { Sysfs.read(it)?.trim()?.toIntOrNull()?.let(curs::add) }
            onLive(caps.capText())
            delay(SWEEP_SAMPLE_MS)
        }
        return SweepRow(
            value = value,
            capMin = caps.minOrNull(),
            capMax = caps.maxOrNull(),
            curMin = curs.minOrNull(),
            curMax = curs.maxOrNull(),
            samples = caps.size,
        )
    }

    /** Release everything the lab holds and re-read the panel. */
    fun resetAll() {
        viewModelScope.launch {
            val message = container.labController.reset()
            _state.value = _state.value.copy(message = message)
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
            groups = _state.value.groups.map { (category, items) ->
                category to items.map { if (it.feature.key == key) transform(it) else it }
            },
        )
    }
}

private const val TAG = "Lab"

/** 自动扫档的节奏：每档 8 秒、0.4 秒采一次。判据是上限节点，不需要负载。 */
private const val SWEEP_HOLD_MS = 8_000
private const val SWEEP_SAMPLE_MS = 400L
