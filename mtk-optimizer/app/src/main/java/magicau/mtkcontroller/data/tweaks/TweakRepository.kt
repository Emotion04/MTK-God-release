package magicau.mtkcontroller.data.tweaks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.privilege.PrivilegeManager

private val Context.tweakStore: DataStore<Preferences> by preferencesDataStore(name = "mtk_optimizer_tweaks")

/**
 * Runs the shell-based tweaks and remembers which ones are on.
 *
 * Every command goes through the elevated UserService, so all of this is a
 * no-op (with a clear message) until Shizuku is granted.
 */
class TweakRepository(
    private val context: Context,
    /** 性能模式切换时停掉/恢复 CPU 频率控制；由 AppContainer 接上 CpuControl。 */
    onPerformanceModePause: suspend (Boolean) -> Unit = {},
) {

    private val brightnessBackendKey = stringPreferencesKey("brightness_backend")
    val brightnessBackend: Flow<BrightnessBackend> = context.tweakStore.data.map { prefs ->
        BrightnessBackend.entries.firstOrNull { it.name == prefs[brightnessBackendKey] } ?: BrightnessBackend.LEGACY
    }
    suspend fun setBrightnessBackend(backend: BrightnessBackend): Outcome = touchMutex.withLock {
        if (thermalBrightness.first() || vivo.hasSnapshot(VivoFeature.BRIGHTNESS)) {
            return@withLock Outcome(false, Message(R.string.tweak_brightness_restore_first))
        }
        if (backend == BrightnessBackend.VIVO && !vivo.supported(VivoFeature.BRIGHTNESS)) {
            return@withLock Outcome(false, Message(R.string.tweak_brightness_unsupported))
        }
        context.tweakStore.edit { it[brightnessBackendKey] = backend.name }
        Outcome(true, Message(R.string.tweak_backend_selected, listOf(Message(backend.labelRes))))
    }

    val vivo = VivoTweaks(context, onPerformanceModePause)
    val gameBoost = GameBoost(context)
    private val touchMutex = Mutex()
    @Volatile var touchRevision: Long = 0
        private set
    private val touchBackendKey = stringPreferencesKey("touch_backend")
    val touchBackend: Flow<TouchBackend> = context.tweakStore.data.map { prefs ->
        TouchBackend.entries.firstOrNull { it.name == prefs[touchBackendKey] } ?: TouchBackend.LEGACY
    }

    suspend fun setTouchBackend(backend: TouchBackend): Outcome = touchMutex.withLock {
        if (touchItems.first().isNotEmpty()) {
            return@withLock Outcome(false, Message(R.string.tweak_touch_close_first))
        }
        if (backend == TouchBackend.VIVO && vivo.touchMax() == null) {
            return@withLock Outcome(false, Message(R.string.tweak_touch_no_vivo))
        }
        context.tweakStore.edit { it[touchBackendKey] = backend.name }
        Outcome(true, Message(R.string.tweak_backend_selected, listOf(Message(backend.labelRes))))
    }

    private val freqCompanionKey = stringPreferencesKey("freq_sched_companion")
    /**
     * 频率范围模式。旧版是布尔 `freq_floor_only`，只够表达"区间/只下限"两种。
     * 换成字符串存枚举名，并读一次旧键做迁移 —— 老用户升上来不会被打回默认值。
     */
    private val freqRangeModeKey = stringPreferencesKey("freq_range_mode")
    private val legacyFreqFloorOnlyKey = booleanPreferencesKey("freq_floor_only")
    private val touchItemsKey = stringSetPreferencesKey("touch_items")
    /** 打开前存下的旋钮原值，编码成 "itemId|NS|key=值"。关闭时写回它。 */
    private val touchOriginalsKey = stringSetPreferencesKey("touch_originals")
    /**
     * 频率请求是否附带下发调度器配合项。**默认关。**
     *
     * 配合项出处见 [TweakCommands.SCHED_COMPANIONS]：厂商跑分场景在把 policy7
     * 软下限顶到 3250000 的同时也发了那几条，我们此前只发频率所以一直被压在 2300。
     *
     * **未在本机验证**，所以做成显式开关而不是默认行为 —— 打开才能测，测完能一键关回去，
     * 不留残留。用户报告结论后再决定是否默认启用、以及默认带哪一套。
     */
    val freqSchedCompanion: Flow<TweakCommands.Companion?> = context.tweakStore.data.map { prefs ->
        val id = prefs[freqCompanionKey] ?: return@map null
        runCatching { TweakCommands.Companion.valueOf(id) }.getOrNull()
    }

    /** 频率请求是否只发下限。默认 false（软硬上限都发）。 */
    val freqRangeMode: Flow<TweakCommands.RangeMode> = context.tweakStore.data.map { prefs ->
        prefs[freqRangeModeKey]?.let { name ->
            TweakCommands.RangeMode.entries.firstOrNull { it.name == name }
        } ?: if (prefs[legacyFreqFloorOnlyKey] == true) {
            TweakCommands.RangeMode.FLOOR_ONLY
        } else {
            TweakCommands.RangeMode.RANGE
        }
    }

    suspend fun setFreqRangeMode(mode: TweakCommands.RangeMode): Outcome {
        context.tweakStore.edit { it[freqRangeModeKey] = mode.name }
        return Outcome(true, Message(R.string.tweak_mode_changed, listOf(Message(mode.labelRes))))
    }

    suspend fun setFreqSchedCompanion(id: String?): Outcome {
        context.tweakStore.edit { prefs ->
            if (id == null) prefs.remove(freqCompanionKey)
            else prefs[freqCompanionKey] = id
        }
        // id 认不出来（旧版本存下的、或已被移除的配合项）当成"没开"，不拼出一个空名。
        val companion = id?.let { TweakCommands.companionMessage(it) }
        return Outcome(
            true,
            if (companion != null) Message(R.string.tweak_companion_on, listOf(companion))
            else Message(R.string.tweak_companion_off),
        )
    }

    private val touchRateKey = intPreferencesKey("touch_rate")
    private val thermalBrightnessKey = booleanPreferencesKey("thermal_brightness")
    private val autoRotateKey = booleanPreferencesKey("auto_rotate")
    private val rotationSuggestionKey = booleanPreferencesKey("rotation_suggestion")
    private val interpKey = booleanPreferencesKey("frame_interpolation")
    private val interpSrKey = booleanPreferencesKey("frame_interpolation_sr")

    /** 已开启的子项 id。总开关的"开/关"由它派生，不再单独存一份。 */
    val touchItems: Flow<Set<String>> = context.tweakStore.data.map { it[touchItemsKey] ?: emptySet() }

    /** 派生状态：有任何一项开着就算触控优化开着。清理代码只认这一个。 */
    val touchEnabled: Flow<Boolean> = touchItems.map { it.isNotEmpty() }

    val touchRate: Flow<Int> = context.tweakStore.data.map { it[touchRateKey] ?: TweakCommands.DEFAULT_TOUCH_RATE }
    val thermalBrightness: Flow<Boolean> = context.tweakStore.data.map { it[thermalBrightnessKey] ?: false }
    val autoRotate: Flow<Boolean> = context.tweakStore.data.map { it[autoRotateKey] ?: false }
    val rotationSuggestion: Flow<Boolean> = context.tweakStore.data.map { it[rotationSuggestionKey] ?: false }
    val frameInterpolation: Flow<Boolean> = context.tweakStore.data.map { it[interpKey] ?: false }
    val frameInterpolationSr: Flow<Boolean> = context.tweakStore.data.map { it[interpSrKey] ?: false }

    /**
     * 把下面几个开关的**本地记录**换成设备的真实状态。
     *
     * ## 为什么必须做
     *
     * 本地存的是"本软件上次把它设成什么"，不是"设备现在是什么"。两者会分叉：
     * 用户在系统设置里改过、别的工具改过、或者重装之后，本地记录就和设备对不上了。
     * 分叉的后果是界面显示假状态 —— 开关看着是关的，用户点一下"打开"，而设备
     * 本来就是开的，于是什么都没发生，用户以为功能坏了。
     *
     * 设备是事实来源，所以每次进页面读一次。**本地只在存"改之前的原值"时才需要**，
     * 那部分不能也不该从设备推导。
     *
     * 读不到的项保持原值不动 —— 读不到不等于关着。
     */
    suspend fun syncSwitchStateFromDevice() = touchMutex.withLock {
        val reads = mapOf(
            rotationSuggestionKey to TweakCommands.readRotationSuggestion(),
            autoRotateKey to TweakCommands.readAutoRotate(),
            interpKey to "settings get system ${TweakCommands.KEY_INTERP}",
            interpSrKey to "settings get system ${TweakCommands.KEY_INTERP_SR}",
        )
        val device = reads.mapValues { (_, command) ->
            // 必须用 VivoReply.output 剥掉用户服务的退出标记包装。直接用 exec 的
            // 返回值会带上 `[exit] 0` 尾巴，拿它和 "1" 比对永远不相等，于是
            // "设备明明开着"被读成关着 —— 这就是开关显示假状态的根源。
            VivoReply.output(PrivilegeManager.exec(command))?.takeIf { it.isNotEmpty() && it != "null" }
        }
        context.tweakStore.edit { prefs ->
            device[rotationSuggestionKey]?.let { prefs[rotationSuggestionKey] = it == "1" }
            device[autoRotateKey]?.let { prefs[autoRotateKey] = it == "1" }
            // 插帧关闭时写的是哨兵值 `0:-1:…`，其余都算开着。第三段是运行时会变的
            // 数，判定只看前两段。
            device[interpKey]?.let { prefs[interpKey] = !TweakCommands.isInterpOff(it) }
            device[interpSrKey]?.let { prefs[interpSrKey] = !TweakCommands.isInterpOff(it) }
        }
    }

    data class Outcome(
        val success: Boolean,
        val message: Message,
        /** 原始错误，默认收起、可复制。界面用 `FailureNote` 展示。 */
        val detail: String? = null,
        val failures: List<String> = emptyList(),
        val needsConfirm: Boolean = false,
    )

    /** Runs every command; reports how many failed rather than aborting on the first. */
    private suspend fun runAll(commands: List<String>): Outcome {
        if (!PrivilegeManager.state.value.mode.canElevate) {
            return Outcome(false, Message(R.string.tweak_need_shizuku))
        }
        val failures = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        for (command in commands) {
            val raw = PrivilegeManager.exec("$command 2>&1")
            if (VivoReply.output(raw) == null) {
                failures += command
                reasons += if (raw == null) {
                    "$command → exec returned null — privilege service unavailable"
                } else {
                    "$command → " + raw.trim().substringBeforeLast("[exit]").trim().ifBlank { "<empty reply>" }
                }
            }
        }
        return if (failures.isEmpty()) {
            Outcome(true, Message(R.string.tweak_commands_done, listOf(commands.size)))
        } else {
            Outcome(
                success = false,
                message = Message(
                    R.string.tweak_commands_result,
                    listOf(commands.size - failures.size, commands.size, failures.size),
                ),
                detail = reasons.joinToString("\n"),
                failures = failures,
            )
        }
    }

    /**
     * 逐项应用触控子项。目标状态由 [onIds] 完整描述，不区分"开"和"关"两条路径。
     *
     * **为什么逐项而不是整族一起跑**：原来二十几条命令一个开关，一条被 SELinux 拒
     * （`device_config put`，退出码 255）就让整个开关打不开；用户也分不出二十几条里
     * 哪一条真的动了。现在每项自己写、自己回读，一项失败只报那一项。
     *
     * 回读用 [TouchKnob.expectedRead]：写进去不等于系统消费，读回一致至少证明
     * "这一项确实落在系统里了"，比只查退出码强 —— `settings put` 退出码 0 但不落库。
     *
     * 关闭写 [TouchKnob.offTarget]（快照优先），不写数据里的兜底值 —— 见 [TouchKnob] 的说明。
     */
    private suspend fun runTouchItems(
        onIds: Set<String>,
        rateHz: Int,
        originals: TouchOriginals,
    ): Pair<Outcome, List<KnobChange>> {
        if (!PrivilegeManager.state.value.mode.canElevate) {
            return Outcome(false, Message(R.string.tweak_need_shizuku)) to emptyList()
        }
        val failures = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        val changes = mutableListOf<KnobChange>()
        var items = 0
        for (item in TouchItems.all(rateHz)) {
            val on = item.id in onIds
            items++
            for (knob in item.knobs) {
                val offValue = knob.offTarget(item.id, originals)
                val value = if (on) knob.onValue else offValue
                // 写之前先读一次现值。不是从快照取 —— 快照是"我们上次存的"，
                // 而系统可能已经改过它。要报告的是**实际发生了什么**，
                // 不是"我们认为应该发生什么"。
                val before = VivoReply.output(PrivilegeManager.exec(knob.readCommand()))?.let {
                    if (it == "null") "" else it
                }.orEmpty()
                // 目标就是"空"时不要真去写。两个原因，都在真机上见过：
                // prop 那边 setprop 给一个本来没设过的属性写空串会报
                // "Failed to set property ... to ''" 并非零退出，会被误判成失败；
                // settings 那边则会把一个本来不存在的键凭空创建出来。
                // 而空值本来就是目标状态，所以跳过写入、只核对读回。
                if (value.isEmpty()) {
                    val read = VivoReply.output(PrivilegeManager.exec(knob.readCommand()))?.let {
                        if (it == "null") "" else it
                    }
                    if (!read.isNullOrEmpty()) failures += "${item.id}:${knob.key}(应当为空，读到 $read)"
                    // 没写也要记录。目标本来就是空，前后必然相同，报出来是"无变化"——
                    // 少报一条，界面上的列表就和实际核对的数量对不上。
                    changes += KnobChange(item.id, knob.key, before, read.orEmpty())
                    continue
                }
                val raw = PrivilegeManager.exec("${knob.command(on, offValue)} 2>&1")
                val reply = VivoReply.output(raw)
                if (reply == null) {
                    failures += "${item.id}:${knob.key}"
                    // "没能送到用户服务"和"送了但被拒"必须分开：前者用户要去连 Shizuku，
                    // 后者是命令本身的事。原来两者都缩成同一个 null，于是一次服务中断
                    // 会被数成"N 处未生效"，用户照着这个数字逐项排查，什么都查不到。
                    reasons += "${item.id}:${knob.key} → " + if (raw == null) {
                        "exec returned null — privilege service unavailable"
                    } else {
                        raw.trim().substringBeforeLast("[exit]").trim().ifBlank { "<empty reply>" }
                    }
                    continue
                }
                // 回读核对。`settings get` 读不到返回 "null"，与 prop 的空串都算"空"。
                val read = VivoReply.output(PrivilegeManager.exec(knob.readCommand()))?.let {
                    if (it == "null") "" else it
                }
                if (read != knob.expectedRead(on, offValue)) {
                    failures += "${item.id}:${knob.key}(回读 ${read ?: "空"})"
                    reasons += "${item.id}:${knob.key} expected=${knob.expectedRead(on, offValue)} read=${read ?: "<null>"}"
                }
                changes += KnobChange(
                    itemId = item.id,
                    key = knob.key,
                    before = before,
                    after = read.orEmpty(),
                )
            }
        }
        // 一项都没选，等于全部关掉 —— 那也是有用的（清干净再切方案）。
        val applied = if (onIds.isEmpty()) {
            Message(R.string.tweak_applied_all_off)
        } else {
            Message(R.string.tweak_applied_items, listOf(onIds.size))
        }
        val outcome = if (failures.isEmpty()) {
            Outcome(true, Message(R.string.tweak_applied_checked, listOf(applied, items)))
        } else {
            Outcome(
                success = false,
                message = Message(R.string.tweak_applied_ineffective, listOf(applied, failures.size)),
                detail = reasons.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                failures = failures,
            )
        }
        return outcome to changes
    }

    /**
     * 一个旋钮这次开关前后的实际值。
     *
     * **为什么需要它**：写后回读只能证明"写进去了"，证明不了"系统理不理睬"。
     * 而人能判断的输入正是这个 —— 开着某项、看到哪些值真的动了，
     * 才知道该去感受哪儿。原先 `runTouchItems` 只收集失败项，
     * 前后值读出来就扔了，等于把唯一有用的信息丢掉了。
     */
    /** 最近一次触控操作里每个旋钮的前后值。界面拿它显示"哪些真的动了"。 */
    @Volatile var lastTouchChanges: List<KnobChange> = emptyList()
        private set

    data class KnobChange(
        val itemId: String,
        val key: String,
        val before: String,
        val after: String,
    ) {
        val changed: Boolean get() = before != after

        /** 给人看的一行：`键 旧值 → 新值`，没有任何变化时简明说没变。 */
        fun describe(): String =
            if (changed) "$key：$before → $after" else "$key：无变化（$before）"
    }

    /**
     * 总开关：开 = 打开全部子项，关 = 关掉全部子项。
     *
     * 逐项开关见 [setTouchItem]。两个入口都归到 [runTouchItems] 一条路，
     * 目标是状态而不是动作，所以不存在"只写开不写关"的半边状态。
     */
    suspend fun setTouchOptimization(enabled: Boolean, rateHz: Int = TweakCommands.DEFAULT_TOUCH_RATE): Outcome =
        touchMutex.withLock {
            if (!PrivilegeManager.state.value.mode.canElevate) return@withLock Outcome(false, Message(R.string.tweak_need_shizuku))
            applyTouchState(
                onIds = if (enabled) TouchItems.all(rateHz).map { it.id }.toSet() else emptySet(),
                rateHz = rateHz,
            )
        }

    /** 单个子项开关。只改这一项，其他项保持原状。 */
    suspend fun setTouchItem(id: String, enabled: Boolean, rateHz: Int = TweakCommands.DEFAULT_TOUCH_RATE): Outcome =
        touchMutex.withLock {
            if (!PrivilegeManager.state.value.mode.canElevate) return@withLock Outcome(false, Message(R.string.tweak_need_shizuku))
            // 固件触控方案是一次原子服务调用（`service call bbk_touch_screen_service`），
            // 没有"逐项"可言。放行的话，只点一项也会打开超出请求的那一整套。
            if (touchBackend.first() == TouchBackend.VIVO) {
                return@withLock Outcome(false, Message(R.string.tweak_touch_no_per_item))
            }
            val current = touchItemIds().toMutableSet()
            if (enabled) current += id else current -= id
            applyTouchState(current, rateHz)
        }

    /**
     * 落库 + 执行。执行失败不回填状态，否则界面显示"开着"而系统里没写。
     *
     * 打开一项之前先存下每个旋钮的现值 —— 关闭时写回它，不写兜底值。原因见 [TouchKnob]：
     * 这台机器 `touch_scan_press_time_delay` 出厂就是 300，写死 0 会把调好的机器改差，
     * 而且读回成功，看不出异常。
     *
     * vivo 后端（`TouchBackend.VIVO`）走另一条路：取值按本机实测定，由 [VivoTweaks]
     * 自己写，不经过 [TouchItems] 的逐项拆分。
     */
    private suspend fun applyTouchState(onIds: Set<String>, rateHz: Int): Outcome {
        touchRevision++
        val originals = readTouchOriginals()
        // 只给"即将打开且没有快照"的项取原值。已有快照的不能再取 —— 那时读到的是上一轮
        // 写入值，覆盖掉它就永远恢复不到出厂值了。
        val fresh = originals.toMutableMap()
        val toSnapshot = TouchItems.all(rateHz).filter { it.id in onIds }.filter { item ->
            item.knobs.any { it.originalKey(item.id) !in originals }
        }
        for (item in toSnapshot) {
            for (knob in item.knobs) {
                val key = knob.originalKey(item.id)
                if (key in fresh) continue
                val read = VivoReply.output(PrivilegeManager.exec(knob.readCommand()))?.let {
                    if (it == "null") "" else it
                }
                // 读不到就记空串而不是跳过：跳过会让下次再取一次，那时已经是写入值了。
                fresh[key] = read.orEmpty()
            }
        }
        if (fresh != originals) context.tweakStore.edit { it[touchOriginalsKey] = fresh.toEncoded() }

        // 先记下目标状态：部分失败或进程退出后也要知道该清理什么。
        context.tweakStore.edit { prefs ->
            prefs[touchItemsKey] = onIds
            if (TouchItems.REPORT_RATE in onIds) prefs[touchRateKey] = rateHz
        }
        val backend = touchBackend.first()
        val (outcome, changes) = if (backend == TouchBackend.VIVO) {
            // 固件方案是一次原子服务调用，没有旋钮可逐条核对，所以没有前后值可报。
            (if (onIds.isEmpty()) vivo.touch(false) else vivo.touch(true)) to emptyList()
        } else {
            runTouchItems(onIds, rateHz, fresh)
        }
        lastTouchChanges = changes
        if (outcome.success && backend != TouchBackend.VIVO) dropRestoredOriginals(onIds, fresh)
        val withVerdict = if (outcome.success) outcome.copy(message = noopNote(onIds, fresh, outcome.message)) else outcome
        return if (withVerdict.success) withVerdict else withVerdict.copy(
            message = Message(R.string.tweak_need_manual_cleanup, listOf(withVerdict.message)),
        )
    }

    /**
     * 出厂值已经等于目标值的子项，打开它是个空动作。
     *
     * 这台机器 `touch_scan_press_time_delay` 出厂就是 300，而开启值正是 300 —— 写进去
     * 等于没写，读回还是成功的。不告诉用户的话，他会把"没变化"记成"这项无效"，
     * 而这个结论是错的：这项在别的 ROM 上可能真的有用。
     *
     * 判据直接来自数据：快照值 == 开启值，全部旋钮都是，就是空动作。
     */
    private fun noopNote(onIds: Set<String>, originals: TouchOriginals, message: Message): Message {
        val noops = TouchItems.all().filter { it.id in onIds }.filter { item ->
            item.knobs.isNotEmpty() && item.knobs.all { knob ->
                originals[knob.originalKey(item.id)] == knob.onValue
            }
        }
        if (noops.isEmpty()) return message
        val names = noops.joinToString("、") { context.getString(it.labelRes) }
        return Message(R.string.tweak_noop_note, listOf(message, names))
    }

    /**
     * 关掉的项不再需要原值了，删掉。
     *
     * 删而不是留着：留着的话下次再打开这一项会读到"上一轮的关闭值"当成原值存下来，
     * 于是恢复来恢复去都是自己的值，永远回不到出厂值。
     */
    private suspend fun dropRestoredOriginals(onIds: Set<String>, originals: TouchOriginals) {
        val keep = originals.filterKeys { key -> key.substringBefore('|') in onIds }
        val encoded = keep.toEncoded()
        if (encoded != readTouchOriginals().toEncoded()) {
            context.tweakStore.edit { it[touchOriginalsKey] = encoded }
        }
    }

    private fun Map<String, String>.toEncoded(): Set<String> =
        map { "${it.key}=${it.value}" }.toSet()

    private suspend fun readTouchOriginals(): TouchOriginals =
        context.tweakStore.data.first()[touchOriginalsKey].orEmpty()
            .mapNotNull { entry ->
                val key = entry.substringBefore('=')
                val value = entry.substringAfter('=')
                if (key.isBlank()) null else key to value
            }.toMap()

    suspend fun stopVivoTouchIfUnchanged(revision: Long) = touchMutex.withLock {
        if (touchRevision == revision && touchBackend.first() == TouchBackend.VIVO) {
            val result = vivo.touch(false)
            if (result.success) context.tweakStore.edit { it[touchItemsKey] = emptySet() }
        }
    }

    suspend fun renewVivoTouch(): Outcome? = touchMutex.withLock {
        if (touchBackend.first() != TouchBackend.VIVO || !touchEnabled.first()) null else vivo.touch(true)
    }

    suspend fun currentTouchRate(): Int = context.tweakStore.data.first()[touchRateKey]
        ?: TweakCommands.DEFAULT_TOUCH_RATE

    /** 当前已开启的子项 id。读一次，用于单个开关的增量修改。 */
    suspend fun touchItemIds(): Set<String> = touchItems.first()

    suspend fun setFrameInterpolation(enabled: Boolean, superResolution: Boolean): Outcome {
        // 之前这里只查 `settings put` 的退出码，而它对任何值都返回 0，于是"开启"写的是
        // 关闭哨兵也照样报成功。现在写后必须回读，读到的值不是目标值就算失败。
        val command = TweakCommands.frameInterpolation(enabled, superResolution)
        val raw = PrivilegeManager.exec(command) ?: return Outcome(false, Message(R.string.tweak_no_service_interp))
        val key = TweakCommands.keyFor(superResolution)
        val expected = TweakCommands.interpValue(enabled)
        val actual = VivoReply.output(PrivilegeManager.exec("settings get system $key"))
        if (actual != expected) {
            return Outcome(
                false,
                Message(
                    R.string.tweak_interp_unconfirmed,
                    listOf(expected, actual ?: context.getString(R.string.value_unreadable)),
                ),
            )
        }
        context.tweakStore.edit {
            if (superResolution) it[interpSrKey] = enabled else it[interpKey] = enabled
        }
        return Outcome(
            true,
            Message(if (enabled) R.string.tweak_interp_on else R.string.tweak_interp_off),
        )
    }

    suspend fun setRotationSuggestion(enabled: Boolean): Outcome {
        val outcome = runAll(listOf(TweakCommands.rotationSuggestion(enabled)))
        if (outcome.success) context.tweakStore.edit { it[rotationSuggestionKey] = enabled }
        return outcome
    }

    suspend fun setAutoRotate(enabled: Boolean): Outcome {
        val outcome = runAll(listOf(TweakCommands.autoRotate(enabled)))
        if (outcome.success) context.tweakStore.edit { it[autoRotateKey] = enabled }
        return outcome
    }

    suspend fun forceRotation(rotation: Int): Outcome = runAll(TweakCommands.forceRotation(rotation))

    suspend fun readAutoRotate(): Boolean {
        val out = PrivilegeManager.exec(TweakCommands.readAutoRotate()) ?: return false
        return out.substringBefore("[exit]").trim() == "1"
    }

    suspend fun setThermalBrightnessWatch(enabled: Boolean): Outcome = touchMutex.withLock {
        if (brightnessBackend.first() == BrightnessBackend.VIVO) {
            val result = vivo.apply(VivoFeature.BRIGHTNESS, restore = !enabled)
            context.tweakStore.edit { it[thermalBrightnessKey] = vivo.hasSnapshot(VivoFeature.BRIGHTNESS) }
            return@withLock result
        }
        context.tweakStore.edit { it[thermalBrightnessKey] = enabled }
        Outcome(true, Message(if (enabled) R.string.tweak_brightness_on else R.string.tweak_brightness_off))
    }

    suspend fun currentBrightness(): Int? {
        val out = PrivilegeManager.exec(TweakCommands.readBrightness()) ?: return null
        return out.substringBefore("[exit]").trim().toIntOrNull()
    }

    suspend fun setBrightness(value: Int): Outcome = runAll(listOf(TweakCommands.setBrightness(value)))
}
