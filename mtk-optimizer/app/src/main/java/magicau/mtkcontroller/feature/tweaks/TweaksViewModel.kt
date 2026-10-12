package magicau.mtkcontroller.feature.tweaks

import magicau.mtkcontroller.data.Failure
import magicau.mtkcontroller.data.tweaks.TweakRepository

import magicau.mtkcontroller.data.tweaks.TweakLifetime
import magicau.mtkcontroller.data.tweaks.TweakValues
import magicau.mtkcontroller.data.tweaks.VivoFeature

import magicau.mtkcontroller.data.tweaks.TouchBackend
import magicau.mtkcontroller.data.diag.SettingChannel
import magicau.mtkcontroller.data.tweaks.TouchItem
import magicau.mtkcontroller.data.tweaks.TouchNs
import magicau.mtkcontroller.data.tweaks.TouchItems

import kotlin.math.roundToInt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.privilege.PrivilegeState
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.feature.thermal.ThermalBrightnessService

data class TweaksUiState(
    val experimentFeature: VivoFeature? = null,
    val experimentRunning: Boolean = false,
    val experimentSeconds: Int = 0,
    val experimentMessage: String? = null,
    val experimentSuccess: Boolean = true,
    val smartInitial: magicau.mtkcontroller.data.tweaks.SmartBackup? = null,
    val smartImported: magicau.mtkcontroller.data.tweaks.SmartBackup? = null,
    val smartPrevious: magicau.mtkcontroller.data.tweaks.SmartBackup? = null,
    val smartInitialNotice: Boolean = false,
    val smartReminder: Boolean = true,
    val smartReadAt: Long = 0,
    val smartValues: Map<String, String> = emptyMap(),
    val smartReport: String = "",
    val tweakReport: String = "",
    val vivoValues: Map<String, String?> = emptyMap(),
    val apps: List<Pair<String, String>> = emptyList(),
    val task: AxTaskState = AxTaskState(),
    val brightnessBackend: magicau.mtkcontroller.data.tweaks.BrightnessBackend = magicau.mtkcontroller.data.tweaks.BrightnessBackend.LEGACY,
    val displaySupported: Boolean = false,
    /** 探测前的占位。界面显示时按语言取资源，这里只是"还没测"的标记。 */
    val displayStatus: String = "",
    val maxDisplayHz: Int = 0,
    val displayHz: Int = 0,
    val session: AxSessionState = AxSessionState(),
    val touchBackend: TouchBackend = TouchBackend.LEGACY,
    val vivoStatus: Map<VivoFeature, String> = emptyMap(),
    val vivoSupported: Set<VivoFeature> = emptySet(),
    /**
     * 本机不支持的功能 → 原因。开关保留但置灰，说明是哪一条没过。
     *
     * 不支持的项不从界面抹掉：用户需要知道这些功能存在、只是这台机器没有。
     */
    val vivoUnsupported: Map<VivoFeature, String> = emptyMap(),
    val vivoSnapshots: Set<VivoFeature> = emptySet(),
    /** 恢复被"期间系统改过"拦住的功能名；再点一次恢复就带 force 放行。 */
    val pendingForceRestore: Set<String> = emptySet(),
    // --- 游戏与画面实验 ---
    val phaseOriginal: Map<String, String?> = emptyMap(),
    val phaseCurrent: Map<String, String?> = emptyMap(),
    val phaseMessage: String = "",
    val phaseSuccess: Boolean = true,
    val gameBoostSelected: String = "",
    val gameBoostActive: Boolean = false,
    val gameBoostStatus: String = "",
    val foregroundApp: String = "",
    val foregroundError: String? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val privilege: PrivilegeState = PrivilegeState(),
    val touchEnabled: Boolean = false,
    /** 已开启的触控子项 id。总开关只图方便，逐项开关才是真正用来判生效的。 */
    val touchItems: Set<String> = emptySet(),

    /**
     * 最近一次触控开关后，每个旋钮的实际前后值。
     *
     * 写后回读只能证明"写进去了"，证明不了"系统理不理睬"。人能判断的输入正是前后值 ——
     * 看到哪些真的动了，才知道该去感受哪儿。原先这两个值读出来就扔了。
     */
    val touchChanges: List<TweakRepository.KnobChange> = emptyList(),
    val touchRate: Int = 360,

    /**
     * 本机实际存在的性能模式键。决定哪几档可点 —— 这台 PD2430A 没有
     * `monster_key_start`，Monster 两档据此置灰；别的机型键存在就照常可点。
     *
     * 空集表示还没探测过。此时不据此置灰任何档位，别让界面刚进来就全灰。
     */
    val perfAvailableKeys: Set<String> = emptySet(),

    /**
     * 只读触控项的当前值，键是 TouchItem.id。
     *
     * 存在这里而不是界面上现读：composable 里做 IO 会在每次重组时重复读一遍，
     * 而这些值只在进页面和刷新时变。
     */
    val touchReadout: Map<String, String> = emptyMap(),
    val frameInterpolation: Boolean = false,
    val frameInterpolationSr: Boolean = false,
    val rotationSuggestion: Boolean = false,
    val thermalBrightness: Boolean = false,
    val lastFailures: List<String> = emptyList(),
    /** 本次操作的结果。摘要与原始错误分两层，界面用 `FailureNote` 展示。 */
    val message: Failure? = null,
    val lastSuccess: Boolean = true,
) {
    val canRun: Boolean get() = privilege.mode.canElevate && !busy

    /**
     * 触控子项与各自开关状态的配对，顺序跟着 [TouchItems]。
     *
     * 派生而不是新增字段：界面要按数据顺序渲染，而"哪些开着"已经存在 [touchItems] 里，
     * 再存一份必然对不上。新增子项只改 `TouchItems`，这里和界面都不用动。
     */
    val touchItemsById: List<Pair<TouchItem, Boolean>>
        get() = TouchItems.all(touchRate).map { it to (it.id in touchItems) }
}

class TweaksViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(TweaksUiState())
    val state: StateFlow<TweaksUiState> = _state.asStateFlow()

    private fun displayInfo(): Pair<Int, Int> {
        val display = container.context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return 0 to 0
        val mode = display.mode
        val hz = display.supportedModes.filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .maxOfOrNull { it.refreshRate }?.roundToInt() ?: 0
        return hz to display.refreshRate.roundToInt()
    }

    fun setDisplay(enabled: Boolean) = run {
        if (enabled) {
            val probe = PrivilegeManager.vivoDisplayRequest(0)
            if (!probe.startsWith("OK:")) {
                // 服务原样回的错误就是设备原文，只包一层格式。
                return@run TweakRepository.Outcome(
                    false,
                    Message(R.string.raw_text, listOf(probe.removePrefix("ERROR:"))),
                )
            }
            val hz = displayInfo().first
            if (hz !in 30..240) {
                return@run TweakRepository.Outcome(false, Message(R.string.tweak_display_no_hz))
            }
            AxSessionService.start(container.context, hz)
        } else AxSessionService.stopDisplay(container.context)
        TweakRepository.Outcome(
            true,
            Message(if (enabled) R.string.tweak_display_on else R.string.tweak_display_off),
        )
    }

    private suspend fun refreshVivo() {
        // 先把几个开关的本地记录换成设备真实状态，再读下面那批。
        // 顺序不能反：本地记录若和设备分叉，界面会显示假状态（看着是关的，
        // 点开却什么都没发生）。读不到时这个方法保持原值不动。
        container.tweakRepository.syncSwitchStateFromDevice()
        val vivo = container.tweakRepository.vivo
        val features = VivoFeature.entries.filter { it != VivoFeature.BRIGHTNESS }
        val values = vivo.currentValues()
        val status = features.associateWith { vivo.describe(it, values) }
        // 两层判据：settings 键 + 门禁（包/prop）。门禁没过的项保留开关但标为不支持 ——
        // 全部从界面上抹掉会让用户以为这些功能不存在，实际上只是这台机器没有。
        val unsupportedReasons = features.associateWith { vivo.probeGate(it) }
        val supported = features.filter { feature ->
            unsupportedReasons[feature] == null && feature.values.keys.all { key ->
                values[key]?.let { feature.allowMissing || it != "null" } == true
            }
        }.toSet()
        val snapshots = features.filter { vivo.hasSnapshot(it) }.toSet()
        val displayProbe = PrivilegeManager.vivoDisplayRequest(0)
        val (maxHz, currentHz) = displayInfo()
        // 门禁没过的项 → 原因。mapNotNull 把 null（支持）滤掉，只留真正不支持的。
        val unsupported = unsupportedReasons.mapNotNull { (f, why) -> why?.let { f to it } }.toMap()
        // 挂起读取结束后再取最新 UI 状态，避免覆盖并行收集到的开关或忙碌状态。
        _state.value = _state.value.copy(vivoValues = values, vivoStatus = status, vivoSupported = supported,
            vivoSnapshots = snapshots, vivoUnsupported = unsupported,
            displaySupported = displayProbe.startsWith("OK:"), displayStatus = displayProbe.substringAfter(":"),
            maxDisplayHz = maxHz, displayHz = currentHz,
            perfAvailableKeys = container.tweakRepository.vivo.availableKeys(),
            touchReadout = readTouchReadout())
    }

    /**
     * 读需要显示当前值的触控项。键是 `"<itemId>|<旋钮键名>"`。
     *
     * 只读项和显式要求显示的项都读。粒度到旋钮而不是整项 —— 按下防抖有四个键，
     * 整项一个值显示不出来。
     */
    private suspend fun readTouchReadout(): Map<String, String> = buildMap {
        for (item in TouchItems.all()) {
            if (!item.showsValues) continue
            for (knob in item.knobs) {
                val value = if (knob.ns == TouchNs.PROP) {
                    // 必须剥掉用户服务的输出包装，否则值里拖着 "[exit] 0"。
                    val exitMarker = "\n" + "[exit] "
                    PrivilegeManager.exec("getprop ${knob.key}")?.substringBefore(exitMarker)?.trim()
                } else {
                    SettingChannel.get(knob.ns.name.lowercase(), knob.key)?.trim()
                }
                if (!value.isNullOrEmpty()) put("${item.id}|${knob.key}", value)
            }
        }
    }

    fun setVivo(feature: VivoFeature, restore: Boolean, option: String?, force: Boolean = false) = run {
        val experiment = feature.lifetime == TweakLifetime.TIMED
        if (experiment) _state.value = _state.value.copy(experimentFeature = feature, experimentRunning = true,
            experimentSeconds = 0, experimentSuccess = true,
            experimentMessage = if (restore) {
                container.context.getString(R.string.tweak_exp_restoring)
            } else {
                container.context.getString(R.string.tweak_exp_running)
            })
        val timer = if (experiment) viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                _state.value = _state.value.copy(experimentSeconds = _state.value.experimentSeconds + 1)
            }
        } else null
        val result = try {
            container.tweakRepository.vivo.apply(feature, restore, option, force)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
                // 异常类名必须留下：摘要只说"操作异常"，证据进 detail 并可复制。
                TweakRepository.Outcome(
                    false,
                    Message(R.string.tweak_op_exception),
                    detail = Failure.detailOf(e),
                )
        }
        finally { timer?.cancel(); if (experiment) _state.value = _state.value.copy(experimentRunning = false) }
        // 先显示控制结果，后续诊断失败不能抹掉写入和恢复的结论。
        if (experiment) {
            _state.value = _state.value.copy(
                experimentMessage = result.message.resolve(container.context),
                experimentSuccess = result.success,
            )
        }
        // 恢复被"系统改过"拦住时记住这个功能，用户再点一次就带 force 放行。
        _state.value = _state.value.copy(
            message = Failure(result.message, result.detail),
            lastSuccess = result.success,
            pendingForceRestore = if (result.needsConfirm) _state.value.pendingForceRestore + feature.name
            else _state.value.pendingForceRestore - feature.name,
            vivoSnapshots = VivoFeature.entries.filter { container.tweakRepository.vivo.hasSnapshot(it) }.toSet())
        try {
        refreshVivo()
        if (feature == VivoFeature.AI) refreshSmartState()
        val report = container.tweakRepository.vivo.diagnosticReport()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            java.io.File(container.context.filesDir, "tweak-report.txt").writeText(report)
        }
        _state.value = _state.value.copy(tweakReport = report)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            val summary = Message(R.string.tweak_report_failed, listOf(result.message, e.message.orEmpty()))
            if (experiment) {
                _state.value = _state.value.copy(
                    experimentMessage = summary.resolve(container.context),
                )
            }
            return@run TweakRepository.Outcome(false, summary)
        }
        result
    }

    fun collectTweakReport() = run {
        val touchLines = buildString {
            append("\n显示=${_state.value.displayHz}Hz 触控方案=${container.context.getString(_state.value.touchBackend.labelRes)} 会话=${_state.value.session}\n")
            // 逐项列出子项与开关状态：导出报告要能回答"这台机器上到底哪几项开着"。
            append(container.context.getString(R.string.report_touch_items))
            _state.value.touchItemsById.forEach { (item, on) ->
                append(
                    "  ${item.id} = " +
                        container.context.getString(
                            if (on) R.string.tweak_touch_item_on else R.string.tweak_touch_item_off,
                        ) +
                        "  证据=${item.evidence.name}\n",
                )
                if (on) item.knobs.forEach { knob ->
                    append("      ${knob.ns.name.lowercase()}:${knob.key} = ${knob.onValue}\n")
                }
            }
        }
        val report = container.tweakRepository.vivo.diagnosticReport() + "\n" +
            magicau.mtkcontroller.data.tweaks.SmartPlatform.report(
                container.context,
                magicau.mtkcontroller.data.tweaks.SmartPlatform.inspect(),
            ) +
            magicau.mtkcontroller.data.tweaks.SmartPlatform.serviceReport() + touchLines
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            java.io.File(container.context.filesDir, "tweak-report.txt").writeText(report)
        }
        _state.value = _state.value.copy(tweakReport = report)
        TweakRepository.Outcome(true, Message(R.string.tweak_report_saved))
    }

    fun exportTweakReport(uri: android.net.Uri) = run {
        val report = _state.value.tweakReport
        check(report.isNotBlank()) {
            container.context.getString(R.string.tweak_report_not_generated)
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            container.context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(report.toByteArray()) }
                ?: error(container.context.getString(R.string.tweak_report_write_failed))
        }
        TweakRepository.Outcome(true, Message(R.string.tweak_report_exported))
    }

    private suspend fun refreshSmartState() {
        val vivo = container.tweakRepository.vivo
        val values = magicau.mtkcontroller.data.tweaks.SmartPlatform.inspect()
        _state.value = _state.value.copy(smartValues = values, smartReadAt = System.currentTimeMillis(),
            smartReport = magicau.mtkcontroller.data.tweaks.SmartPlatform.report(container.context, values),
            smartInitial = vivo.smartBackup(), smartImported = vivo.smartBackup("imported"),
            smartPrevious = vivo.smartBackup("previous"), smartReminder = vivo.remindSmartBackup())
    }

    fun enterSmartPlatform() = run {
        val first = container.tweakRepository.vivo.smartBackup() == null
        _state.value = _state.value.copy(smartInitialNotice = first)
        container.tweakRepository.vivo.captureSmartInitial()
        refreshSmartState()
        refreshVivo()
        val backup = _state.value.smartInitial!!
        TweakRepository.Outcome(
            true,
            if (first) {
                Message(R.string.tweak_smart_initial, listOf(backup.values.size, backup.missing.size))
            } else {
                Message(R.string.tweak_smart_read)
            },
        )
    }

    fun dismissSmartNotice() { _state.value = _state.value.copy(smartInitialNotice = false) }
    fun setSmartReminder(enabled: Boolean) {
        container.tweakRepository.vivo.setSmartReminder(enabled)
        _state.value = _state.value.copy(smartReminder = enabled)
    }
    fun restoreSmart(slot: String) = run {
        val result = container.tweakRepository.vivo.restoreSmartBackup(slot)
        refreshSmartState()
        refreshVivo()
        result
    }
    fun exportSmart(uri: android.net.Uri, slot: String) = run {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val backup = container.tweakRepository.vivo.smartBackup(slot) ?: error(container.context.getString(R.string.tweak_smart_no_backup_export))
            container.context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(magicau.mtkcontroller.data.tweaks.SmartBackupCodec.encode(backup).toByteArray())
            } ?: error(container.context.getString(R.string.tweak_smart_write_failed))
        }
        TweakRepository.Outcome(true, Message(R.string.tweak_smart_exported))
    }
    fun importSmart(uri: android.net.Uri) = run {
        val backup = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bytes = container.context.contentResolver.openInputStream(uri)?.use { it.readNBytes(magicau.mtkcontroller.data.tweaks.SmartBackupCodec.MAX_BYTES + 1) }
                ?: error(container.context.getString(R.string.tweak_smart_read_failed))
            magicau.mtkcontroller.data.tweaks.SmartBackupCodec.decode(bytes.decodeToString())
        }
        container.tweakRepository.vivo.importSmartBackup(backup)
        _state.value = _state.value.copy(smartImported = backup)
        TweakRepository.Outcome(true, Message(R.string.tweak_smart_imported))
    }

    fun refreshVivoCapabilities() = run {
        refreshVivo()
        TweakRepository.Outcome(true, Message(R.string.tweak_refreshed))
    }

    fun inspectSmartPlatform() = run {
        refreshSmartState()
        refreshVivo()
        val values = _state.value.smartValues
        TweakRepository.Outcome(
            values.isNotEmpty(),
            Message(if (values.isEmpty()) R.string.tweak_inspect_empty else R.string.tweak_inspect_done),
        )
    }

    fun runMaintenance(kind: String, pkg: String) {
        if (_state.value.task.running || !_state.value.canRun) return
        viewModelScope.launch {
            try { AxTaskService.start(container.context, kind, pkg) }
            catch (e: Exception) {
                _state.value = _state.value.copy(
                    message = Failure.from(
                        "AxTask",
                        Message(R.string.tweak_task_start_failed, listOf(e.message.orEmpty())),
                        e,
                    ),
                    lastSuccess = false,
                )
            }
        }
    }

    // --- 游戏与画面实验 ------------------------------------------------------

    /**
     * SF 相位偏移。原值先在 UI 侧读一次存进 state，恢复时逐项写回，
     * 而不是无脑写 0 —— 0 只是"不启用"，不一定是本 ROM 的原始默认值。
     */
    fun setPhaseOffset(prop: String, ns: Long) {
        if (!_state.value.canRun) return
        viewModelScope.launch {
            val result = runCatching { magicau.mtkcontroller.data.tweaks.PhaseOffset.set(prop, ns) }
                .getOrElse { "ERROR:${it.javaClass.simpleName}: ${it.message}" }
            _state.value = _state.value.copy(phaseMessage = result, phaseSuccess = result.startsWith("OK"))
            refreshPhaseOffsets()
        }
    }

    fun snapshotPhaseOffsets() {
        viewModelScope.launch {
            val snap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                magicau.mtkcontroller.data.tweaks.PhaseOffset.snapshot()
            }
            _state.value = _state.value.copy(phaseOriginal = snap)
        }
    }

    fun restorePhaseOffsets() {
        if (!_state.value.canRun) return
        viewModelScope.launch {
            val snap = _state.value.phaseOriginal
            if (snap.isEmpty()) {
                _state.value = _state.value.copy(phaseMessage = container.context.getString(R.string.tweak_phase_no_original), phaseSuccess = false)
                return@launch
            }
            val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                magicau.mtkcontroller.data.tweaks.PhaseOffset.restore(snap)
            }
            _state.value = _state.value.copy(
                phaseMessage = report,
                phaseSuccess = !report.contains(container.context.getString(R.string.phase_restore_failed_marker)),
            )
            refreshPhaseOffsets()
        }
    }

    private fun refreshPhaseOffsets() {
        viewModelScope.launch {
            val current = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                magicau.mtkcontroller.data.tweaks.PhaseOffset.snapshot()
            }
            _state.value = _state.value.copy(phaseCurrent = current)
        }
    }

    fun fakeBattery(command: String) {
        if (!_state.value.canRun) return
        viewModelScope.launch {
            val reply = PrivilegeManager.fakeBattery(command)
            val ok = reply.startsWith("OK")
            // 服务端回的是 "OK:<shell 命令>" 或 "ERROR:<原因>"。那条 shell 命令是留给
            // 固件侧对质用的，不进摘要 —— 用户不需要知道我们跑了哪条 dumpsys。
            val serverNote = reply.substringAfter(':', "").trim().takeIf { it.isNotBlank() }
            val message = if (!ok) {
                // 失败时不要去核对"系统显示在不在充电"：那时候读到的任何状态都说明不了
                // 本次设置的结果，只会给一句文不对题的结论。
                Failure(Message(R.string.tweak_fake_failed), detail = serverNote)
            } else {
                val faked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (command == "reset") null else magicau.mtkcontroller.data.privilege.FakeBattery.fakedOrUnknown()
                }
                Failure(
                    when {
                        command == "reset" -> Message(R.string.tweak_fake_reset_done)
                        faked == true -> Message(R.string.tweak_fake_conflict)
                        faked == false -> Message(R.string.tweak_fake_real)
                        else -> Message(R.string.tweak_fake_unconfirmed)
                    },
                    detail = serverNote,
                )
            }
            _state.value = _state.value.copy(message = message, lastSuccess = ok)
        }
    }

    fun selectGameBoostApp(pkg: String) {
        viewModelScope.launch {
            container.tweakRepository.gameBoost.selected = pkg
            _state.value = _state.value.copy(gameBoostStatus = "", gameBoostSelected = pkg)
            refreshGameBoostStatus()
        }
    }

    fun applyGameBoost() {
        if (!_state.value.canRun) return
        viewModelScope.launch {
            val outcome = container.tweakRepository.gameBoost.apply()
            _state.value = _state.value.copy(
                message = Failure(outcome.message, outcome.detail),
                lastSuccess = outcome.success,
            )
            refreshGameBoostStatus()
        }
    }

    fun restoreGameBoost() {
        if (!_state.value.canRun) return
        viewModelScope.launch {
            val outcome = container.tweakRepository.gameBoost.restore()
            _state.value = _state.value.copy(
                message = Failure(outcome.message, outcome.detail),
                lastSuccess = outcome.success,
            )
            refreshGameBoostStatus()
        }
    }

    fun refreshGameBoostStatus() {
        if (!_state.value.canRun) return
        viewModelScope.launch {
            val active = container.tweakRepository.gameBoost.hasSnapshot()
            val status = runCatching { container.tweakRepository.gameBoost.status() }
                .getOrElse {
                container.context.getString(R.string.tweak_read_failed_msg, it.message.orEmpty())
            }
            _state.value = _state.value.copy(gameBoostActive = active, gameBoostStatus = status)
        }
    }

    fun refreshForegroundApp() {
        if (!_state.value.canRun) return
        viewModelScope.launch {
            val reply = PrivilegeManager.foregroundApp()
            _state.value = _state.value.copy(
                foregroundApp = if (reply.startsWith("ERROR")) "" else reply,
                foregroundError = reply.takeIf { it.startsWith("ERROR") }?.substringAfter(':'),
            )
        }
    }

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val saved = runCatching { java.io.File(container.context.filesDir, "tweak-report.txt").readText() }.getOrDefault("")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (_state.value.tweakReport.isEmpty()) _state.value = _state.value.copy(tweakReport = saved)
            }
        }
        val vivo = container.tweakRepository.vivo
        _state.value = _state.value.copy(smartInitial = vivo.smartBackup(), smartImported = vivo.smartBackup("imported"),
            smartPrevious = vivo.smartBackup("previous"), smartReminder = vivo.remindSmartBackup())
        viewModelScope.launch {
            AxTaskService.state.collect { task -> _state.value = _state.value.copy(task = task) }
        }
        viewModelScope.launch {
            val apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val pm = container.context.packageManager
                pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }.sortedBy { it.second }
            }
            _state.value = _state.value.copy(apps = apps)
        }
        viewModelScope.launch {
            container.tweakRepository.brightnessBackend.collect { backend ->
                _state.value = _state.value.copy(brightnessBackend = backend)
            }
        }
        viewModelScope.launch {
            AxSessionService.state.collect { session ->
                _state.value = _state.value.copy(session = session, displayHz = displayInfo().second)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.touchBackend.collect { backend ->
                _state.value = _state.value.copy(touchBackend = backend)
            }
        }
        viewModelScope.launch {
            PrivilegeManager.state.collect { privilege ->
                _state.value = _state.value.copy(loading = false, privilege = privilege)
                if (privilege.mode.canElevate) refreshVivo()
            }
        }
        viewModelScope.launch {
            container.tweakRepository.touchEnabled.collect { enabled ->
                _state.value = _state.value.copy(touchEnabled = enabled)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.touchItems.collect { ids ->
                _state.value = _state.value.copy(touchItems = ids)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.touchRate.collect { rate ->
                _state.value = _state.value.copy(touchRate = rate)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.frameInterpolation.collect { enabled ->
                _state.value = _state.value.copy(frameInterpolation = enabled)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.frameInterpolationSr.collect { enabled ->
                _state.value = _state.value.copy(frameInterpolationSr = enabled)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.rotationSuggestion.collect { enabled ->
                _state.value = _state.value.copy(rotationSuggestion = enabled)
            }
        }
        viewModelScope.launch {
            container.tweakRepository.thermalBrightness.collect { enabled ->
                _state.value = _state.value.copy(thermalBrightness = enabled)
            }
        }
    }

    private fun run(block: suspend () -> TweakRepository.Outcome) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null, lastFailures = emptyList())
            val outcome = try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                TweakRepository.Outcome(
                    false,
                    Message(R.string.tweak_op_failed),
                    detail = Failure.detailOf(e),
                )
            }
            finally { _state.value = _state.value.copy(busy = false) }
            _state.value = _state.value.copy(
                busy = false,
                message = Failure(outcome.message, outcome.detail),
                lastSuccess = outcome.success,
                lastFailures = outcome.failures,
            )
        }
    }

    fun setTouch(enabled: Boolean) = run {
        val result = container.tweakRepository.setTouchOptimization(enabled, _state.value.touchRate)
        _state.value = _state.value.copy(touchChanges = container.tweakRepository.lastTouchChanges)
        if (result.success && enabled && _state.value.touchBackend == TouchBackend.VIVO) {
            try { AxSessionService.start(container.context) }
            catch (e: Exception) { container.tweakRepository.setTouchOptimization(false); throw e }
        }
        result
    }

    fun setTouchRate(rate: Int) =
        run { container.tweakRepository.setTouchOptimization(true, rate) }

    /** 选中了本机不支持的性能档位：把原因显示出来，不发起写入。 */
    fun reportPerfUnsupported(levelId: String) {
        val level = TweakValues.perfLevel(levelId)
        _state.value = _state.value.copy(
            message = Failure(
                if (level == null) {
                    Message(R.string.vivo_pick_perf)
                } else {
                    Message(
                        R.string.tweak_perf_unsupported,
                        listOf(Message(level.labelRes)),
                    )
                },
            ),
            lastSuccess = false,
        )
    }

    /** 单个触控子项开关。只改这一项，其他项不动。 */
    fun setTouchItem(id: String, enabled: Boolean) = run {
        val result = container.tweakRepository.setTouchItem(id, enabled, _state.value.touchRate)
        _state.value = _state.value.copy(touchChanges = container.tweakRepository.lastTouchChanges)
        result
    }

    fun setFrameInterpolation(enabled: Boolean) =
        run { container.tweakRepository.setFrameInterpolation(enabled, superResolution = false) }

    fun setFrameInterpolationSr(enabled: Boolean) =
        run { container.tweakRepository.setFrameInterpolation(enabled, superResolution = true) }

    fun setRotationSuggestion(enabled: Boolean) =
        run { container.tweakRepository.setRotationSuggestion(enabled) }

    fun setThermalBrightness(enabled: Boolean) {
        run {
            val outcome = container.tweakRepository.setThermalBrightnessWatch(enabled)
            if (outcome.success) {
                if (enabled && _state.value.brightnessBackend == magicau.mtkcontroller.data.tweaks.BrightnessBackend.LEGACY) {
                    try { ThermalBrightnessService.start(container.context) }
                    catch (e: Exception) { container.tweakRepository.setThermalBrightnessWatch(false); throw e }
                } else ThermalBrightnessService.stop(container.context)
            }
            outcome
        }
    }

    fun requestPermission() {
        PrivilegeManager.requestPermission()
        PrivilegeManager.refresh()
    }
}
