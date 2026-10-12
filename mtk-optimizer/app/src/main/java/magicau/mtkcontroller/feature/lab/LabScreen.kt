package magicau.mtkcontroller.feature.lab

import magicau.mtkcontroller.ui.components.SUB_SCREEN_EXTRA_PADDING
import magicau.mtkcontroller.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import magicau.mtkcontroller.data.lab.LabCategory
import magicau.mtkcontroller.data.lab.LabChoice
import magicau.mtkcontroller.data.lab.LabControl
import magicau.mtkcontroller.data.lab.LabCores
import magicau.mtkcontroller.data.lab.LabController
import magicau.mtkcontroller.data.lab.LabFeature
import magicau.mtkcontroller.data.lab.LabRisk
import magicau.mtkcontroller.data.lab.THERMAL_POLICY_KEY
import magicau.mtkcontroller.data.thermal.ThermalPolicyController
import magicau.mtkcontroller.ui.theme.isDarkScheme
import magicau.mtkcontroller.ui.theme.adaptiveAccent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import magicau.mtkcontroller.ui.theme.LocalShapeStyle

private val RiskLow = Color(0xFF2E7D32)
private val RiskMedium = Color(0xFFB4531A)
private val RiskHigh = Color(0xFF9C4146)
private val StateOk = Color(0xFF2E7D32)
private val StateBad = Color(0xFF9C4146)
private val StateNeutral = Color(0xFF6B6B68)

/**
 * The canary lab: features whose protocol is understood but which have not been
 * shown to work on *this* device.
 *
 * Each card answers the same three questions in the same order — how risky is
 * it, has it been observed working here, and what exactly did the device do when
 * tried. Opening the screen applies nothing; a card only sends when the user
 * presses 测试.
 */
@Composable
fun LabScreen(
    state: LabUiState,
    onOpenPanel: () -> Unit,
    onOpenRawTest: () -> Unit,
    onValueChange: (LabFeature, String, Int) -> Unit,
    onHoldChange: (LabFeature, Boolean) -> Unit,
    onTest: (LabFeature) -> Unit,
    onSweep: (LabFeature, Boolean) -> Unit,
    onResetAll: () -> Unit,
    onClearObservations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Column(
            modifier = modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = SUB_SCREEN_EXTRA_PADDING),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "协议上成立，但没在你这台机器上验证过。每张卡片都会读回系统状态，" +
                        "直接告诉你有没有生效 —— 测出来能用就可以移出实验室。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.powerHalReady) {
                    Text(
                        "调频服务当前不可用，写入不会成功。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onOpenPanel, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.lab_readonly_panel))
                    }
                    FilledTonalButton(onClick = onOpenRawTest, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.lab_raw_test))
                    }
                    FilledTonalButton(onClick = onResetAll) { Text(stringResource(R.string.lab_reset)) }
                }
                state.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        state.groups.forEach { (category, items) ->
            item(key = "header-${category.name}") { CategoryHeader(category) }
            items.forEach { item ->
                item(key = item.feature.key) {
                    FeatureCard(
                        item = item,
                        clusters = state.clusters.map { it.policy },
                        // 只有温控档位那一项有"当前生效"可显示；其余功能没有共享状态。
                        liveThermal = if (item.feature.key == THERMAL_POLICY_KEY &&
                            state.thermalAppliedIndex != null && state.thermalAppliedSource != null
                        ) {
                            state.thermalAppliedIndex to state.thermalAppliedSource
                        } else {
                            null
                        },
                        onValueChange = { policy, value -> onValueChange(item.feature, policy, value) },
                        onHoldChange = { onHoldChange(item.feature, it) },
                        onTest = { onTest(item.feature) },
                        onSweep = { stop -> onSweep(item.feature, stop) },
                    )
                }
            }
        }

        item {
            FilledTonalButton(onClick = onClearObservations, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lab_clear_observations))
            }
        }
    }
}

@Composable
private fun CategoryHeader(category: LabCategory) {
    Text(
        category.title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun FeatureCard(
    item: LabItemState,
    clusters: List<String>,
    /** 固件当前生效的温控档位与它的来源；非温控卡片传 null。 */
    liveThermal: Pair<Int, ThermalPolicyController.Source>? = null,
    onValueChange: (String, Int) -> Unit,
    onHoldChange: (Boolean) -> Unit,
    onTest: () -> Unit,
    onSweep: (Boolean) -> Unit,
) {
    val feature = item.feature

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(feature.label, style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 运行时选项（温控策略索引）给一个「档位说明」：一次看完全部档位
                    // 在干什么，不用逐项点。内容来自 assets，换 ROM 改那份文件即可。
                    if (item.choices.isNotEmpty()) {
                        var showNotes by remember { mutableStateOf(false) }
                        TextButton(onClick = { showNotes = true }) { Text(stringResource(R.string.policy_notes_open)) }
                        if (showNotes) {
                            PolicyNotesDialog(briefs = item.briefs, onDismiss = { showNotes = false })
                        }
                    }
                    Badge("风险 ${feature.risk.label}", riskColor(feature.risk))
                    VerdictBadge(item.verdict)
                }
            }

            Text(
                feature.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            feature.node?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            feature.danger?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            item.readout?.let { readout ->
                Text(
                    readout,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // 当前**固件上**生效的档位，以及是谁要求的。来自共享状态，不是这里读的 ——
            // 温控档位有三个来源，只有一处真源才不会三处各显示各的。
            liveThermal?.let { (index, source) ->
                Text(
                    stringResource(R.string.lab_thermal_live, index, sourceLabel(source)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // 当前选中档位的规则摘要：只有温度和频率，现读现解来的。
            // 参数原文不显示 —— 那部分留待将来的「高级」开关。
            item.selectedBrief?.let { brief ->
                Text(
                    brief,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (item.selectedBrief == null && item.briefs.isNotEmpty()) {
                Text(
                    stringResource(R.string.policy_notes_loading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (item.supported == false) {
                Text(
                    "本机没有这一项，功能不可用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            Controls(
                feature = feature,
                item = item,
                clusters = clusters,
                onValueChange = onValueChange,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.lab_hold_long), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (item.hold) {
                            "请求永久有效。必须用「恢复全部」撤销,否则会一直生效。"
                        } else {
                            "20 秒后由系统自动撤销。测试够用了,而且就算 App 崩了、" +
                                "句柄丢了,也不会留下卡死的频率。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.hold) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(checked = item.hold, onCheckedChange = onHoldChange)
            }

            Button(
                onClick = onTest,
                enabled = !item.busy && item.supported != false && item.sweepProgress == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (item.busy) "测试中…" else "测试") }

            // 取值语义没有公开文档的功能：一个一个手拖既慢又容易漏档，
            // 还难保证上一档的请求已经释放。 sweepValues 非空才给这个入口。
            if (feature.sweepValues != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { onSweep(true) },
                        enabled = item.supported != false && item.sweepProgress == null,
                        modifier = Modifier.weight(1f),
                    ) { Text("扫到生效即停", style = MaterialTheme.typography.labelLarge) }
                    FilledTonalButton(
                        onClick = { onSweep(false) },
                        enabled = item.supported != false && item.sweepProgress == null,
                        modifier = Modifier.weight(1f),
                    ) { Text("扫完全部 ${feature.sweepValues.size} 档", style = MaterialTheme.typography.labelLarge) }
                }
            }

            // 扫档期间只留进度：逐档的"读回失败"在扫档里是纯噪声 ——
            // 这一族本来就没有可读节点，每档弹一次只会把结果表挤下去。
            if (item.sweepProgress != null) {
                Text(
                    item.sweepProgress,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (item.sweepResults.isNotEmpty()) {
                SweepTable(item.sweepResults)
            }

            if (item.sweepProgress == null) {
                item.verdict?.let { verdict ->
                    Text(
                        verdict.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = verdictColor(verdict.stateEnum()),
                    )
                }
            }
        }
    }
}

/** 扫档结果：一档一行。上限抖成一串说明那档没作用，只有整档一致地偏高才算信号。 */
@Composable
private fun SweepTable(rows: List<SweepRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("扫档结果：上限 / 当前频率", style = MaterialTheme.typography.labelMedium)
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (row.isBaseline) "基线" else "索引 ${row.value.toString().padStart(2)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(0.3f),
                )
                Text(
                    "${row.capText}  /  ${row.curText}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(0.7f),
                )
            }
        }
    }
}

@Composable
private fun Controls(
    feature: LabFeature,
    item: LabItemState,
    clusters: List<String>,
    onValueChange: (String, Int) -> Unit,
) {
    when (feature.control) {
        // 一个开关渲染**一个键**：普通功能只有一个键（GLOBAL），逐核功能有 8 个。
        // 不给逐核单开一个分支 —— 那会把同一套开关逻辑抄两遍。
        LabControl.TOGGLE -> {
            item.values.keys.forEach { key ->
                val checked = (item.values[key] ?: 0) != 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (feature.perCore) key else if (checked) "开" else "关",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (feature.perCore) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(checked = checked, onCheckedChange = { onValueChange(key, if (it) 1 else 0) })
                }
            }
        }

        LabControl.CHOICE -> {
            // 选项可能来自设备本身（温控策略索引就是列 /vendor/etc/thermal/ 得来的），
            // 所以优先用运行时解析出的那份；静态 valueChoices 只是兜底。
            val choices = item.choices.ifEmpty {
                feature.valueChoices.map { LabChoice(it.second, it.first, "") }
            }
            val key = item.values.keys.firstOrNull() ?: LabViewModel.GLOBAL
            val current = item.values[key] ?: choices.firstOrNull()?.value ?: 0
            if (choices.size <= 4) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    choices.forEachIndexed { index, choice ->
                        SegmentedButton(
                            selected = current == choice.value,
                            onClick = { onValueChange(key, choice.value) },
                            shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                        ) { Text(choice.label) }
                    }
                }
            } else {
                // 档位多的时候不要一列一个 chip —— 那会把卡片撑到十几行高。
                // 横向滚动单行；每个档干什么统一从「档位说明」一个入口看。
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(choices) { choice ->
                        FilterChip(
                            selected = current == choice.value,
                            onClick = { onValueChange(key, choice.value) },
                            label = { Text(choice.label) },
                        )
                    }
                }
            }

        }

        LabControl.SLIDER -> {
            val keys = when {
                feature.perCore -> LabCores.keys
                feature.perCluster -> clusters
                else -> listOf(LabViewModel.GLOBAL)
            }
            keys.forEach { policy ->
                val value = item.values[policy] ?: feature.range.first
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (feature.perCluster || feature.perCore) policy else "目标值",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "$value${feature.unit}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Slider(
                        value = value.toFloat(),
                        onValueChange = { onValueChange(policy, it.roundToInt()) },
                        valueRange = feature.range.first.toFloat()..feature.range.last.toFloat(),
                    )
                }
            }
        }

        LabControl.READONLY -> Unit
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    val tint = color.adaptiveAccent(isDarkScheme())
    Box(
        modifier = Modifier
            .clip(LocalShapeStyle.current.chip)
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun VerdictBadge(verdict: LabController.Verdict?) {
    Badge(verdict?.stateEnum()?.label ?: LabController.State.UNTESTED.label, verdictColor(verdict?.stateEnum() ?: LabController.State.UNTESTED))
}

@Composable
private fun riskColor(risk: LabRisk): Color = when (risk) {
    LabRisk.LOW -> RiskLow
    LabRisk.MEDIUM -> RiskMedium
    LabRisk.HIGH -> RiskHigh
}

@Composable
private fun verdictColor(state: LabController.State): Color = when (state) {
    LabController.State.WORKING -> StateOk
    LabController.State.NO_EFFECT, LabController.State.UNSUPPORTED -> StateBad
    LabController.State.UNKNOWN, LabController.State.UNTESTED -> StateNeutral
}

/**
 * 一次展示全部温控策略档位在干什么。
 *
 * 内容来自 `assets/thermal-policies.md` —— **不写死在代码里**：
 * 那是解码分析得出的结论，会随 ROM 版本失效；换 ROM 只改那份文件，
 * 不用重新编译。
 */
@Composable
private fun PolicyNotesDialog(briefs: Map<Int, String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val fallback = remember(context) {
        runCatching {
            context.assets.open("thermal-policies.md").bufferedReader().use { it.readText() }
        }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.policy_notes_title)) },
        text = {
            Box(modifier = Modifier.heightIn(max = 460.dp)) {
                SelectionContainer {
                    LazyColumn {
                        if (briefs.isNotEmpty()) {
                            // 有现读的摘要就优先用它 —— 永远是设备当下的事实。
                            briefs.toSortedMap().forEach { (index, brief) ->
                                item(key = "brief-$index") {
                                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                        Text(
                                            stringResource(R.string.policy_notes_index, index),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            brief,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }
                        } else if (!fallback.isNullOrBlank()) {
                            // 读不到文件时退回随包说明，并说明这不是实时的。
                            item {
                                Text(
                                    stringResource(R.string.policy_notes_stale),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            item {
                                Text(
                                    fallback,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        } else {
                            item {
                                Text(
                                    stringResource(R.string.policy_notes_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}


/**
 * "现在这一档是谁在管"的一句话。
 *
 * 为什么值得显示：温控档位有三个来源，用户在下达命令的那一页看不到另外两个。
 * 不说明来源的话，他在 CPU 页选了 03，实验室这里显示 05，看起来就是"设置没用"。
 */
@Composable
private fun sourceLabel(source: ThermalPolicyController.Source): String = stringResource(
    when (source) {
        ThermalPolicyController.Source.USER -> R.string.thermal_source_user
        ThermalPolicyController.Source.APP_RULE -> R.string.thermal_source_app_rule
        ThermalPolicyController.Source.LAB -> R.string.thermal_source_lab
    },
)
