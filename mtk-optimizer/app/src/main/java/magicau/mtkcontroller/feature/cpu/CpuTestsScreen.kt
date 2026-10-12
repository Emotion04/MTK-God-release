package magicau.mtkcontroller.feature.cpu

import magicau.mtkcontroller.ui.components.FailureNote
import magicau.mtkcontroller.ui.components.SUB_SCREEN_EXTRA_PADDING
import magicau.mtkcontroller.ui.text
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import magicau.mtkcontroller.data.cpu.CpuProbeMode
import magicau.mtkcontroller.ui.theme.LocalShapeStyle

@Composable
fun CpuTestsScreen(
    state: CpuUiState,
    onResume: () -> Unit,
    onProbe: (Int, CpuProbeMode) -> Unit,
    onStopProbe: () -> Unit,
    onProbeAll: (Int) -> Unit,
    onExportProbe: (android.net.Uri, String) -> Unit,
    onCollectRomConfigs: () -> Unit,
    onRange: (Int, Int, Int) -> Unit,
    onRelease: () -> Unit,
    onOpenFeatureTrial: () -> Unit = {},
) {
    LifecycleResumeEffect(Unit) { onResume(); onPauseOrDispose { } }
    var legacyExpanded by remember { mutableStateOf(false) }
    var archiveToExport by remember { mutableStateOf("") }
    val archiveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
        // ui-check:ignore 导出协议前缀，不是给用户看的文案
    ) { uri -> if (uri != null) onExportProbe(uri, "ROM_ZIP:$archiveToExport") }
    var probeIndex by remember { mutableStateOf(0) }
    var pendingProbe by remember { mutableStateOf<CpuProbeMode?>(null) }
    var pendingAll by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> if (uri != null) onExportProbe(uri, exportText) }
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view, state.probing) {
        val previous = view.keepScreenOn
        if (state.probing) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    if (pendingAll) AlertDialog(
        onDismissRequest = { pendingAll = false },
        title = { Text("开始完整对照测试") },
        text = { Text("约 2 分钟，请留在本页。结束自动保存报告，可随时停止。") },
        confirmButton = { TextButton(onClick = { pendingAll = false; onProbeAll(probeIndex) }) { Text("开始记录") } },
        dismissButton = { TextButton(onClick = { pendingAll = false }) { Text("取消") } },
    )
    pendingProbe?.let { mode ->
        AlertDialog(onDismissRequest = { pendingProbe = null },
            title = { Text(mode.label) },
            text = { Text(if (mode == CpuProbeMode.ROM)
                "只读采集，不写频率、不改温控。通常数秒。"
                else if (mode == CpuProbeMode.CAMERA)
                "约 60 秒，按提示开关一次相机。只读采集，不加负载。"
                else if (mode == CpuProbeMode.VIVO_SOURCE_MAX)
                "下发 5 秒探针请求，结束后自动释放。"
                else "测试当前选定的范围，结束后尝试恢复。") },
            confirmButton = { TextButton(onClick = { pendingProbe = null; onProbe(probeIndex, mode) }) { Text("开始测试") } },
            dismissButton = { TextButton(onClick = { pendingProbe = null }) { Text("取消") } })
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = SUB_SCREEN_EXTRA_PADDING),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TestSection("频率策略快照", "只读采集热配置、模式状态和可访问的系统接口，不修改频率或温控。") {
                Text("开始前先释放 CPU 设置。", style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = onRelease, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("释放 CPU 设置") }
                Button(onClick = { pendingProbe = CpuProbeMode.ROM }, enabled = !state.busy && state.edits.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("开始只读快照") }
            }
        }

        if (state.message != null) item { FailureNote(failure = state.message) }
        if (state.probing) item {
            TestSection("正在记录", state.probeStage) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                FilledTonalButton(onClick = onStopProbe, modifier = Modifier.fillMaxWidth()) { Text("停止并清理请求") }
            }
        }
        item {
            TestSection("功能逐个试用", "一次只开一项，由你判断有没有变化。判定和键值一起导出，适配新机型直接用。") {
                FilledTonalButton(onClick = onOpenFeatureTrial, modifier = Modifier.fillMaxWidth()) {
                    Text("进入功能试用")
                }
            }
        }
        item {
            TestSection("测试报告", "结束后自动保存在应用内。导出到本地文件可长期保留。") {
                if (state.probeSaveNote.isNotBlank()) Text(state.probeSaveNote, style = MaterialTheme.typography.bodySmall)
                if (state.probeReport.isBlank()) Text("暂无报告", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    FilledTonalButton(enabled = !state.probing, modifier = Modifier.fillMaxWidth(), onClick = {
                        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("测试报告", state.probeReport))
                    }) { Text("复制报告") }
                    Button(enabled = !state.probing, modifier = Modifier.fillMaxWidth(), onClick = {
                        exportText = state.probeReport
                        exportLauncher.launch("cpu-probe-${System.currentTimeMillis()}.txt")
                    }) { Text("导出 TXT 报告") }
                    var preview by remember { mutableStateOf(false) }
                    TextButton(onClick = { preview = !preview }) { Text(if (preview) "收起预览" else "查看报告末尾") }
                    if (preview) Text(state.probeReport.takeLast(2500), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            TestSection("实现包", "只读提取系统性能接口与实现文件。与频率测试报告分开导出，无需切换模式。") {
                FilledTonalButton(onClick = onCollectRomConfigs, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("采集实现文件") }
                if (state.configArchive.isNotBlank()) {
                    Text(state.configArchive, style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(enabled = !state.busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        archiveToExport = state.configArchive
                        archiveLauncher.launch(state.configArchive)
                    }) { Text("导出 ZIP 实现包") }
                }
            }
        }
        item {
            TestSection("历史测试", "旧版排查项目，日常使用无需运行。展开后选择参数与测试。") {
                FilledTonalButton(onClick = { legacyExpanded = !legacyExpanded }, modifier = Modifier.fillMaxWidth()) { Text(if (legacyExpanded) "收起历史测试" else "展开历史测试") }
                if (legacyExpanded) {
                    state.edits.forEachIndexed { index, edit ->
                        FilterChip(selected = probeIndex == index, onClick = { probeIndex = index }, enabled = !state.busy, label = { Text(edit.cluster.policy) })
                    }
                    state.edits.getOrNull(probeIndex)?.let { edit ->
                        Text("${edit.cluster.policy}，${edit.minFreqKhz / 1000}–${edit.maxFreqKhz / 1000} MHz")
                        if (edit.cluster.availableFreqs.size > 1) RangeSlider(
                            value = edit.minIndex.toFloat()..edit.maxIndex.toFloat(),
                            onValueChange = { onRange(probeIndex, it.start.toInt(), it.endInclusive.toInt()) },
                            valueRange = 0f..edit.cluster.availableFreqs.lastIndex.toFloat(),
                            steps = (edit.cluster.availableFreqs.size - 2).coerceAtLeast(0), enabled = !state.busy,
                        )
                    }
                    Text("范围仅供历史测试使用，不会自动应用。", style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = { pendingAll = true }, enabled = !state.busy && state.powerHal.available, modifier = Modifier.fillMaxWidth()) { Text("完整负载对照") }
                    CpuProbeMode.entries.filter { it != CpuProbeMode.VIVO_POLICY_9 && it != CpuProbeMode.VIVO_POLICY_71 && it != CpuProbeMode.ROM }.forEach { mode ->
                        FilledTonalButton(enabled = !state.busy && state.edits.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = { pendingProbe = mode }) { Text(mode.label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TestSection(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = LocalShapeStyle.current.card) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            magicau.mtkcontroller.ui.components.InfoHeading(title, description)
            content()
        }
    }
}
