package magicau.mtkcontroller.data.cpu

import android.content.Context
import kotlinx.coroutines.delay
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import magicau.mtkcontroller.data.diag.SettingChannel
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.powerhal.PerfLockController
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.domain.model.ClusterSetting
import magicau.mtkcontroller.domain.model.CpuCluster
import magicau.mtkcontroller.data.tweaks.TweakCommands

/**
 * The one owner of CPU frequency control.
 *
 * Everything that touches PowerHAL's CPU frequency resources goes through here —
 * the CPU screen, the home dashboard, the profile list and the re-apply service
 * all call [apply] and [release] and nothing else. That is deliberate: the
 * release-then-acquire sequence used to be copy-pasted into six call sites, and
 * every bug in this area was one of them forgetting a step.
 *
 * The request-side invariants (release before acquire, never forget an
 * unreleased handle, serialise) live in [PerfLockController], which is shared
 * with the lab so there is exactly one implementation of them.
 *
 * What is specific to CPU frequency is the *verification*: limits are read back
 * from the kernel because the service returning a handle says nothing about
 * whether the value landed, and because libpowerhal merges every live request —
 * one stale lock makes later ceilings look ignored.
 */
class CpuControl(
    /**
     * 只用来解析资源。
     *
     * 这个类拿不到 Context 时只能把中文写成字面量；而它的结论**要同时**给界面
     * 和日志，还有几处是逐簇拼接的（见 `updateVerification`）—— 那种串没法只
     * 靠返回一个 `Message` 表达，需要当场解析。`data/` 里别的类本来就用 Context。
     */
    private val context: Context,
    private val store: CpuSettingsStore,
    private val lock: PerfLockController,
    private val readNodes: suspend (List<String>) -> Map<String, String?> = Sysfs::readMany,
    private val waitForSample: suspend (Long) -> Unit = { delay(it) },
    private val createProbeLoad: () -> AutoCloseable = { CpuProbeLoad() },
    private val vivoRequest: suspend (Boolean) -> String = PrivilegeManager::vivoCpuLimitProbe,
    private val vivoPolicyRequest: suspend (Boolean, Int) -> String = PrivilegeManager::vivoPolicyProbe,
    /**
     * 频率请求要附带的调度器配合项。默认空 = 不带。
     *
     * 做成参数而不是在类里读设置：单元测试要能分别验"带"和"不带"两条路径，
     * 而读 DataStore 需要 Context，测试里没有。
     */
    private val schedCompanions: () -> List<Pair<Int, String>> = { emptyList() },
    /**
     * true = 只发下限，不发任何上限。
     *
     * 厂商高性能场景（`powerscntbl.xml` 3160）就是这么做的。我们在高频段发上限，
     * 会被 HAL 翻译成一个等于其表内 `freqMax` 的帽子 —— 详情见
     * [TweakCommands.RangeMode]。
     */
    private val rangeMode: () -> TweakCommands.RangeMode = { TweakCommands.RangeMode.RANGE },
) {

    private companion object {
        const val TAG = "CpuControl"

        /**
         * When to read the limits back after an apply or a release.
         *
         * libpowerhal applies a command array **one entry at a time** and the
         * transaction returns before that finishes, so a single early read
         * cannot tell "not applied yet" from "never applied".
         *
         * A single 400 ms read was the previous behaviour, and it left exactly
         * that ambiguity open: on-device logs showed a batch accepted with a
         * valid handle whose values had not moved 400 ms later. Sampling more
         * than once separates the two cases, and the last sample is the one
         * reported as the result.
         */
        const val SETTLE_MS = 400L

        val SAMPLE_DELAYS_MS = longArrayOf(400L, 1_500L, 3_000L)
    }

    /** success acknowledges the request operation; effectiveness lives in Status.verification. */
    data class Outcome(
        val success: Boolean,
        val message: Message,
        /** 原始错误，默认收起、可复制。界面用 `FailureNote` 展示。 */
        val detail: String? = null,
    )

    data class Status(
        val handler: Int = 0,
        val baseline: String? = null,
        val applied: List<ClusterSetting> = emptyList(),
        val observedLimits: String? = null,
        /** True when the kernel does not match the baseline after a release. */
        val releaseIncomplete: Boolean = false,
        val verification: CpuVerification = CpuVerification.UNVERIFIED,
        val verificationDetail: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val operationMutex = Mutex()
    private var lastVerificationKey: String? = null

    /** Pull persisted state into memory. Safe to call more than once. */
    suspend fun load() = operationMutex.withLock {
        val handler = lock.handler()
        val baseline = store.baseline()
        val applied = store.lastApplied()
        _status.value = _status.value.copy(handler = handler, baseline = baseline, applied = applied)
        AppLog.i(TAG, "载入状态: handler=$handler, 已应用 ${applied.size} 组设置")
    }

    suspend fun lastApplied(): List<ClusterSetting> = store.lastApplied()

    /** Exclusive, finite probe. Never persists an experimental setting for polling. */
    suspend fun probe(
        clusters: List<CpuCluster>, policy: String, low: Long, high: Long,
        mode: CpuProbeMode, emit: (String) -> Unit,
    ): Outcome = operationMutex.withLock {
        if (mode == CpuProbeMode.VIVO_LIMIT || mode == CpuProbeMode.VIVO_POLICY_9 || mode == CpuProbeMode.VIVO_POLICY_71 || mode == CpuProbeMode.VIVO_SOURCE_MAX) {
            val profile = when (mode) {
                CpuProbeMode.VIVO_POLICY_9 -> 9
                CpuProbeMode.VIVO_POLICY_71 -> 71
                CpuProbeMode.VIVO_SOURCE_MAX -> 100
                else -> 0
            }
            val duration = when (profile) { 0 -> 8000; 100 -> 5000; else -> 2000 }
            suspend fun request(start: Boolean) = if (profile == 0) vivoRequest(start) else vivoPolicyRequest(start, profile)
            check(store.lastApplied().isEmpty() && lock.handler() <= 0) { "请先释放现有 CPU 请求" }
            emit(if (mode == CpuProbeMode.VIVO_SOURCE_MAX)
                "ROM CPUFreqPolicy.BOOST_CPU_MAX_FREQ 候选；三簇 MAX=4095、5 秒、不加负载；用于判断能否解除其他请求的上限"
                else "Vivo 独立资源验证；不使用滑条、不加负载、可能影响全部簇；非范围成功测试")
            suspend fun snapshot(label: String) {
                val begin = System.nanoTime()
                val readings = readSnapshot(clusters)
                emit("$label readMs=${(System.nanoTime() - begin) / 1_000_000} ${readings.joinToString { it.describe() }}")
            }
            repeat(3) { snapshot("Vivo 写前 $it"); waitForSample(1000) }
            val start = System.nanoTime()
            var returnedAt = start
            try {
                val result = withContext(NonCancellable) { request(true) }
                returnedAt = System.nanoTime()
                emit(result)
                if (!result.contains("ACCEPTED")) {
                    return@withLock Outcome(false, Message(R.string.cpu_vivo_rejected))
                }
                repeat(if (profile == 100) 7 else 5) {
                    waitForSample(when (profile) { 0 -> 700; 100 -> 700; else -> 200 })
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    snapshot("Vivo 写后 elapsed=${elapsed}ms duration=${duration}ms ${if (elapsed >= duration) "已过名义期限，不用于效果判定" else "名义期限内，仍需结合读取耗时"}")
                }
            } finally {
                withContext(NonCancellable) {
                    val release = runCatching { request(false) }
                    emit(release.getOrElse { "Vivo 释放异常：${it.message}；等待有限请求到期" })
                    val elapsed = (System.nanoTime() - returnedAt) / 1_000_000
                    waitForSample((duration + 1000 - elapsed).coerceAtLeast(1000))
                    repeat(3) { snapshot("Vivo 释放并等待到期后 $it"); waitForSample(500) }
                }
            }
            return@withLock Outcome(true, Message(R.string.cpu_probe_accepted))
        }
        if (mode == CpuProbeMode.CAMERA || mode == CpuProbeMode.ROM) {
            return@withLock Outcome(false, Message(R.string.cpu_probe_external))
        }
        val cluster = clusters.firstOrNull { it.policy == policy }
            ?: return@withLock Outcome(false, Message(R.string.cpu_probe_no_cluster))
        val lo = minOf(cluster.nearest(low), cluster.nearest(high))
        val hi = maxOf(cluster.nearest(low), cluster.nearest(high))
        val pairs = mode.pairs(cluster.index, lo, hi)
        val saved = store.lastApplied()
        val started = System.nanoTime()
        val busy = CpuBusySampler()
        suspend fun sample(label: String) {
            val readings = readSnapshot(clusters)
            val utilization = busy.sample(readNodes(listOf("/proc/stat"))["/proc/stat"])
            emit("$label elapsed=${(System.nanoTime() - started) / 1_000_000}ms " +
                readings.joinToString { it.describe() } + " busy=[$utilization]（分次读取）")
        }
        emit("模式=${mode.label} policy=$policy 请求=$lo-$hi kHz 原设置=$saved")
        sample("开始")
        if (mode == CpuProbeMode.OBSERVE) {
            repeat(30) { waitForSample(1000); sample("观察 ${it + 1}/30") }
            return@withLock Outcome(true, Message(R.string.cpu_probe_observe_done))
        }
        if (lock.handler() > 0 && saved.isEmpty())
            return@withLock Outcome(false, Message(R.string.cpu_probe_handler_no_settings))
        var touched = false
        var restored = true
        var load: AutoCloseable? = null
        try {
            withContext(NonCancellable) {
                if (lock.handler() > 0) {
                    val released = lock.release()
                    check(released.success) { released.message }
                }
                touched = true
                store.setLastApplied(emptyList())
                _status.value = _status.value.copy(handler = 0, applied = emptyList(), verification = CpuVerification.UNVERIFIED)
            }
            waitForSample(1000)
            sample("释放原请求后")
            if (mode.loaded) {
                load = createProbeLoad()
                emit("计算负载开始：最多 8 个线程，不绑定核心；20 秒自动截止，阶段结束提前停止")
                waitForSample(2000)
                sample("普通负载预热后，尚未写入测试请求")
            }
            emit("测试资源=$pairs duration=8000ms")
            val acquired = lock.apply(pairs, durationMs = 8000)
            check(acquired.success) { acquired.message }
            val acceptedAt = System.nanoTime()
            emit("测试句柄=${lock.handler()}")
            repeat(8) {
                waitForSample(1000)
                val age = (System.nanoTime() - acceptedAt) / 1_000_000
                sample("测试 ${it + 1}/8 acquire返回后=${age}ms ${if (age >= 8000) "已越过请求时长，不用于约束判定" else "请求名义时长内，仍需结合实际采样耗时"}")
            }
        } finally {
            load?.close()
            if (mode.loaded) emit("计算负载已停止")
            if (touched) withContext(NonCancellable) {
                val released = if (lock.handler() > 0) {
                    lock.release()
                } else {
                    PerfLockController.Outcome(true, Message(R.string.lock_no_test_handler))
                }
                emit("测试释放=${released.success} ${AppLog.text(released.message)}")
                if (released.success) {
                    waitForSample(1000)
                    sample("测试释放后")
                    if (saved.isNotEmpty()) {
                        val result = applyLocked(clusters, saved)
                        restored = result.success
                        emit("恢复原请求=${result.success} ${AppLog.text(result.message)}")
                    } else emit("原请求为空，保持释放")
                } else {
                    restored = false
                    _status.value = _status.value.copy(handler = lock.handler())
                    emit("恢复中止：测试释放失败；有限请求应到期，请检查读回后手动释放")
                }
            }
        }
        Outcome(
            restored,
            Message(if (restored) R.string.cpu_probe_done else R.string.cpu_probe_restore_failed),
        )
    }

    /**
     * Remember the untouched limits while nothing of ours is applied.
     *
     * This is what lets a later release be checked: "did the kernel go back to
     * where it started", which a oneway release call cannot tell us.
     */
    suspend fun captureBaselineIfClean(clusters: List<CpuCluster>) {
        if (store.baseline() != null) return
        val limits = hardwareRange(clusters) ?: return
        store.setBaseline(limits)
        _status.value = _status.value.copy(baseline = limits)
        AppLog.i(TAG, "记录硬件范围: $limits")
    }

    /**
     * Last-resort recovery: write the hardware range straight into the kernel.
     *
     * PowerHAL cannot loosen anything. Its merge is `floor = max(all floors)` and
     * `ceiling = min(all ceilings)`, so a request whose handle was lost keeps
     * clamping the cluster and no new request can undo it — the only ways out
     * are releasing that exact handle (impossible once it is lost) or rebooting.
     *
     * Writing `scaling_max_freq`/`scaling_min_freq` through the elevated shell
     * bypasses PowerHAL entirely and is the one non-reboot route left. It only
     * works where those nodes are writable by the elevated uid: with Shizuku
     * started as root yes, over adb usually not. Each cluster is reported
     * separately so a partial recovery is visible rather than assumed.
     *
     * Ceiling is written before floor so `min <= max` holds at every moment.
     */
    suspend fun emergencyRestore(clusters: List<CpuCluster>): Outcome = operationMutex.withLock {
        withContext(NonCancellable) { emergencyRestoreLocked(clusters) }
    }

    private suspend fun emergencyRestoreLocked(clusters: List<CpuCluster>): Outcome {
        if (clusters.isEmpty()) return Outcome(false, Message(R.string.cpu_no_clusters))

        val before = readLimits(clusters)
        AppLog.i(TAG, "紧急恢复开始,当前: ${before ?: "读取失败"}")

        if (lock.handler() > 0) {
            val released = lock.release()
            if (!released.success) return Outcome(false, released.message, detail = released.detail)
        }
        store.setLastApplied(emptyList())
        _status.value = _status.value.copy(handler = 0, applied = emptyList(),
            verification = CpuVerification.UNVERIFIED, verificationDetail = null)

        // Route 1 — write the hardware range straight into the kernel. Only
        // works where those nodes are writable by the elevated uid, which means
        // Shizuku started as root; over adb (uid 2000) it usually is not.
        //
        // 失败原因必须原样留下：紧急恢复是用户已经"卡在高频下不来"的时候按的按钮，
        // 那时候最需要知道的就是"哪个簇、什么权限错误"，而不是一句"没成功"。
        val failures = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        clusters.forEach { cluster ->
            val dir = "${CpuScanner.CPUFREQ_ROOT}/${cluster.policy}"
            val hardwareMax = Sysfs.read("$dir/cpuinfo_max_freq")
            val hardwareMin = Sysfs.read("$dir/cpuinfo_min_freq")
            if (hardwareMax == null || hardwareMin == null) {
                failures += cluster.policy
                reasons += "$dir/cpuinfo_{min,max}_freq 读不到"
                return@forEach
            }
            // Ceiling first so min <= max holds at every moment.
            val maxError = Sysfs.writeOrNull("$dir/scaling_max_freq", hardwareMax)
            val minError = Sysfs.writeOrNull("$dir/scaling_min_freq", hardwareMin)
            AppLog.i(
                TAG,
                "紧急恢复[直接写] ${cluster.policy}: max=$hardwareMax(${if (maxError == null) "成功" else "失败"}) " +
                    "min=$hardwareMin(${if (minError == null) "成功" else "失败"})",
            )
            if (maxError != null || minError != null) {
                failures += cluster.policy
                reasons += buildList {
                    maxError?.let { add("$dir/scaling_max_freq → $it") }
                    minError?.let { add("$dir/scaling_min_freq → $it") }
                }.joinToString("\n")
            }
        }
        val directWriteDetail = reasons.takeIf { it.isNotEmpty() }?.joinToString("\n")
        delay(SETTLE_MS)
        val afterDirect = readLimits(clusters)
        if (afterDirect != null && afterDirect != before) {
            _status.value = _status.value.copy(handler = 0, applied = emptyList(), observedLimits = afterDirect)
            return Outcome(true, Message(R.string.cpu_direct_write, listOf(afterDirect)))
        }

        // Route 2 — make the platform rewrite the limits itself by cycling
        // power-save. `settings` is writable by the shell uid, so this works
        // over adb where the sysfs write does not: toggling the mode makes the
        // vendor power service re-evaluate and re-push every limit, which
        // overwrites the stale one.
        AppLog.i(TAG, "紧急恢复[直接写] 无效,改用切换省电模式让平台重算")
        // 走 SettingChannel：这台 ROM 上 `settings put` 退出码 0 但不落库，
        // 这条紧急恢复路径因此一直是空操作。见 SettingChannel 的说明。
        val original = SettingChannel.get("global", "low_power")?.trim()?.take(1)
        SettingChannel.put("global", "low_power", "1")
        delay(SETTLE_MS)
        SettingChannel.put("global", "low_power", "0")
        delay(SETTLE_MS * 3)
        // Put the user's own setting back rather than assuming it was off.
        if (original == "1") SettingChannel.put("global", "low_power", "1")

        val afterCycle = sampleLimits(clusters, "紧急恢复")
        _status.value = _status.value.copy(handler = 0, applied = emptyList(), observedLimits = afterCycle)

        return when {
            afterCycle != null && afterCycle != before ->
                Outcome(true, Message(R.string.cpu_cycle_applied, listOf(afterCycle)))
            failures.size == clusters.size ->
                Outcome(false, Message(R.string.cpu_both_failed), detail = directWriteDetail)
            else ->
                Outcome(
                    false,
                    Message(
                        R.string.cpu_restore_failed,
                        listOf(afterCycle ?: context.getString(R.string.value_read_failed)),
                    ),
                    detail = directWriteDetail,
                )
        }
    }

    /**
     * `cpuinfo_min_freq`/`cpuinfo_max_freq` — the silicon's own range.
     *
     * The reference for "did the kernel come back" has to be something that was
     * never ours to move. An earlier version snapshotted `scaling_min_freq` at
     * whatever moment the screen first opened, which on an already-clamped
     * device records the *clamped* value as normal and then never notices that
     * releases do nothing.
     */
    suspend fun hardwareRange(clusters: List<CpuCluster>): String? {
        if (clusters.isEmpty()) return null
        val parts = clusters.mapNotNull { cluster ->
            val dir = "${CpuScanner.CPUFREQ_ROOT}/${cluster.policy}"
            val lo = Sysfs.read("$dir/cpuinfo_min_freq")?.toLongOrNull()
            val hi = Sysfs.read("$dir/cpuinfo_max_freq")?.toLongOrNull()
            if (lo == null || hi == null) null
            else "${cluster.policy} ${lo / 1000}-${hi / 1000}MHz"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * Apply [settings], replacing whatever we were holding.
     *
     * The previous request is released first and the whole sequence aborts if
     * that release fails, because acquiring on top of a live request is what
     * makes a cluster appear locked at a value nobody asked for.
     */
    suspend fun apply(clusters: List<CpuCluster>, settings: List<ClusterSetting>): Outcome = operationMutex.withLock {
        applyLocked(clusters, settings)
    }

    /** Read replay state only after entering the same transaction as apply/release. */
    suspend fun reapply(clusters: List<CpuCluster>): Outcome = operationMutex.withLock {
        val settings = store.lastApplied()
        if (settings.isEmpty()) return@withLock Outcome(true, Message(R.string.cpu_no_replay_settings))
        if (settings.any { setting -> clusters.none { it.policy == setting.policy } }) {
            return@withLock Outcome(false, Message(R.string.cpu_replay_cluster_gone))
        }
        val observed = readSnapshot(clusters)
        val checks = settings.map { setting -> observed.first { it.policy == setting.policy }.verify(setting) }
        if (lock.handler() > 0 && checks.all { it == CpuVerification.VERIFIED || it == CpuVerification.CONSTRAINED }) {
            updateVerification(settings, observed)
            return@withLock Outcome(true, Message(R.string.cpu_limit_still_in_range))
        }
        applyLocked(clusters, settings)
    }

    private suspend fun applyLocked(clusters: List<CpuCluster>, settings: List<ClusterSetting>): Outcome {
        if (clusters.isEmpty()) return Outcome(false, Message(R.string.cpu_no_clusters))
        if (settings.isEmpty()) return Outcome(false, Message(R.string.cpu_no_cluster_selected))

        val byPolicy = clusters.associateBy { it.policy }
        if (settings.map { it.policy }.distinct().size != settings.size || settings.any { it.policy !in byPolicy }) {
            return Outcome(false, Message(R.string.cpu_duplicate_cluster))
        }
        val normalized = mutableListOf<ClusterSetting>()
        val pairs = mutableListOf<Pair<String, String>>()
        for (setting in settings) {
            val cluster = byPolicy[setting.policy] ?: continue
            val low = cluster.nearest(setting.minFreqKhz)
            val high = cluster.nearest(setting.maxFreqKhz)
            val floor = minOf(low, high)
            val ceiling = maxOf(low, high)
            if (floor <= 0 || ceiling > Int.MAX_VALUE) {
                return Outcome(false, Message(R.string.cpu_freq_out_of_range))
            }
            normalized += setting.copy(minFreqKhz = floor, maxFreqKhz = ceiling)

            // Soft ceilings can be raised by another scenario's soft floor.
            // Keep an independent hard ceiling; only a point also requests a hard floor.
            if (rangeMode() != TweakCommands.RangeMode.CEILING_ONLY) {
                pairs += PowerHal.commandId(PowerHal.BASE_MIN, cluster.index) to floor.toString()
            }
            // 上限可省。见 [rangeMode] 与 [TweakCommands.RangeMode]。
            if (rangeMode() != TweakCommands.RangeMode.FLOOR_ONLY) {
                pairs += PowerHal.commandId(PowerHal.BASE_MAX, cluster.index) to ceiling.toString()
            }
                        // **区间必须带硬下限。**
            //
            // 2026-09-24 真机逐项实测（vivo PD2430A，「实验室 → 逐 ID 测试」）：
            //   软 MIN = 3300000                       →  无反应，仍被常驻上限压在 2300
            //   硬 MIN_HL=300000 + MAX_HL=1600000      →  上限 1600 准确，下限被硬件钳到
            //                                             600（policy7 的 cpuinfo_min_freq），
            //                                             不是钉在 300 —— 硬下限是**下限**
            //   硬 MIN_HL=800000 + MAX_HL=1600000      →  完全符合预期
            //   硬 MIN_HL=2900000 及以上               →  超出系统包络后退化成"钉在硬下限"
            //
            // 结论：硬上下限**能做区间**，只是超出系统允许的包络（本机约 2000–2300）后
            // 退化成钉死。而只在软族里发下限，请求就完全暴露在那个常驻上限之下。
            //
            // 早前这里刻意不给区间发硬下限，理由是"硬上下限凑齐就等于定点锁"——
            // 那个判断只在超出包络时成立，在包络内是错的。已改。
            // 只压上限时不发下限 —— 低频段交给系统自己调度。
            if (rangeMode() != TweakCommands.RangeMode.CEILING_ONLY) {
                pairs += PowerHal.commandId(PowerHal.BASE_HARD_MIN, cluster.index) to floor.toString()
            }
            if (rangeMode() != TweakCommands.RangeMode.FLOOR_ONLY) {
                pairs += PowerHal.commandId(PowerHal.BASE_HARD_MAX, cluster.index) to ceiling.toString()
            }
            AppLog.i(TAG, "${cluster.policy} " + if (floor == ceiling) {
                "单值请求 ${floor / 1000}MHz"
            } else {
                "频率范围 ${floor / 1000}-${ceiling / 1000}MHz"
            } + "（${rangeMode().name}）")
        }
        // 附带下发调度器配合项（用户显式开启时才非空）。
        // 单独一组，不掺进 pairs 的归一化逻辑 —— 它们没有 floor/ceiling 语义，
        // 也不需要 nearest() 钳制，掺进去只会让区间判读变糊。
        val companion = schedCompanions()
        val all = if (companion.isEmpty()) pairs else pairs + companion.map { (opcode, value) ->
            PowerHal.fixedCommandId(opcode) to value
        }
        if (companion.isNotEmpty()) {
            AppLog.i(TAG, "附带调度器配合项 ${companion.size} 条: $companion")
        }
        if (all.isEmpty()) return Outcome(false, Message(R.string.cpu_no_matching_cluster))

        // duration 0: a limit the user asked for should stay until released.
        // Once Binder has accepted an acquire, cancellation must not lose its handle or replay state.
        val applied = withContext(NonCancellable) {
            lock.apply(all, durationMs = 0).also { result ->
                if (result.success) {
                    store.setLastApplied(normalized)
                    _status.value = _status.value.copy(handler = lock.handler(), applied = normalized,
                        observedLimits = null, verification = CpuVerification.UNVERIFIED, releaseIncomplete = false)
                } else {
                    val remaining = lock.handler()
                    if (remaining <= 0) {
                        store.setLastApplied(emptyList())
                        _status.value = _status.value.copy(handler = 0, applied = emptyList(),
                            verification = CpuVerification.UNVERIFIED, verificationDetail = null)
                    } else {
                        _status.value = _status.value.copy(handler = remaining)
                    }
                }
            }
        }
        if (!applied.success) return Outcome(false, applied.message)

        val governorNote = applyGovernors(clusters, normalized)

        val observed = sampleSnapshot(clusters, "应用 handler=${lock.handler()}")
        val detail = updateVerification(normalized, observed)

        _status.value = _status.value.copy(
            handler = lock.handler(),
            applied = normalized,
            observedLimits = observed.joinToString(", ") { it.describe() },
            releaseIncomplete = false,
        )

        return Outcome(
            success = true,
            // 调速器提示可有可无；两段都是资源，拼接交给带占位符的模板。
            message = governorNote?.let { Message(R.string.cpu_outcome_join, listOf(detail, it)) } ?: detail,
        )
    }

    /**
     * Release our request and report whether the kernel came back.
     *
     * A oneway release returning success proves nothing, so the result is judged
     * by reading the limits back against the baseline recorded before we ever
     * touched anything.
     */
    suspend fun release(clusters: List<CpuCluster>): Outcome = operationMutex.withLock {
        releaseLocked(clusters)
    }

    private suspend fun releaseLocked(clusters: List<CpuCluster>): Outcome {
        val released = withContext(NonCancellable) {
            val result = if (lock.handler() <= 0) {
                PerfLockController.Outcome(true, Message(R.string.lock_no_recorded_request))
            } else {
                lock.release()
            }
            result.also {
                if (result.success) {
                    store.setLastApplied(emptyList())
                    _status.value = _status.value.copy(handler = 0, applied = emptyList(),
                        verification = CpuVerification.UNVERIFIED, verificationDetail = null)
                }
            }
        }
        if (!released.success) return Outcome(false, released.message)

        val after = sampleLimits(clusters, "释放")

        AppLog.i(TAG, "释放后读回: ${after ?: "上下限不可读"}；平台仍可能施加限制")
        lastVerificationKey = null

        _status.value = _status.value.copy(
            handler = 0,
            applied = emptyList(),
            observedLimits = after,
            releaseIncomplete = false,
        )

        return Outcome(
            success = true,
            message = if (after != null) Message(R.string.cpu_release_done_current, listOf(after))
            else Message(R.string.cpu_release_done),
        )
    }

    /**
     * Read the limits back at several delays and return the settled reading.
     *
     * Every sample is logged. Changes may be settling or another platform writer;
     * the samples alone cannot attribute the cause. Only the final sample is returned.
     */
    private suspend fun sampleLimits(clusters: List<CpuCluster>, phase: String): String? =
        sampleSnapshot(clusters, phase).takeIf { rows -> rows.any { it.min != null || it.max != null } }
            ?.joinToString(", ") { it.describe() }

    private suspend fun sampleSnapshot(clusters: List<CpuCluster>, phase: String): List<CpuLimitReading> {
        var previousDelay = 0L
        var settled = emptyList<CpuLimitReading>()
        val started = System.nanoTime()
        for (delayMs in SAMPLE_DELAYS_MS) {
            waitForSample(delayMs - previousDelay)
            previousDelay = delayMs
            val sample = readSnapshot(clusters)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            AppLog.d(TAG, "内核实际上下限($phase elapsed=${elapsed}ms target=${delayMs}ms): ${sample.joinToString { it.describe() }}")
            settled = sample
        }
        return settled
    }

    /**
     * Current `scaling_min_freq` / `scaling_max_freq` per cluster, in MHz.
     * Null when no cluster could be read.
     */
    suspend fun readLimits(clusters: List<CpuCluster>): String? {
        if (clusters.isEmpty()) return null
        return readSnapshot(clusters).takeIf { rows -> rows.any { it.min != null || it.max != null } }
            ?.joinToString(", ") { it.describe() }
    }

    private suspend fun readSnapshot(clusters: List<CpuCluster>): List<CpuLimitReading> {
        val paths = clusters.flatMap { cluster ->
            listOf("scaling_min_freq", "scaling_max_freq", "scaling_cur_freq").map {
                "${CpuScanner.CPUFREQ_ROOT}/${cluster.policy}/$it"
            }
        }
        val values = readNodes(paths)
        return clusters.map { cluster ->
            val dir = "${CpuScanner.CPUFREQ_ROOT}/${cluster.policy}"
            CpuLimitReading(cluster.policy, values["$dir/scaling_min_freq"]?.toLongOrNull(),
                values["$dir/scaling_max_freq"]?.toLongOrNull(), values["$dir/scaling_cur_freq"]?.toLongOrNull())
        }
    }

    private fun updateVerification(settings: List<ClusterSetting>, observed: List<CpuLimitReading>): Message {
        val rows = settings.map { setting ->
            val reading = observed.firstOrNull { it.policy == setting.policy }
                ?: CpuLimitReading(setting.policy, null, null, null)
            setting to reading
        }
        val checks = rows.map { (setting, reading) -> reading.verify(setting) }
        val verification = when {
            CpuVerification.CONFLICT in checks -> CpuVerification.CONFLICT
            CpuVerification.UNREADABLE in checks -> CpuVerification.UNREADABLE
            CpuVerification.CONSTRAINED in checks -> CpuVerification.CONSTRAINED
            else -> CpuVerification.VERIFIED
        }
        // 逐簇的读数行**既是报告字段也是给用户看的结论**，所以用资源拼。
        val detail = rows.joinToString("; ") { (setting, reading) ->
            val reason = when (reading.verify(setting)) {
                CpuVerification.UNREADABLE -> context.getString(R.string.cpu_verify_unreadable)
                CpuVerification.CONFLICT -> reading.conflictReason(setting).resolve(context)
                CpuVerification.CONSTRAINED -> context.getString(R.string.cpu_verify_constrained)
                else -> context.getString(R.string.cpu_verify_ok)
            }
            context.getString(
                R.string.cpu_verify_row,
                reading.describe(),
                setting.minFreqKhz / 1000,
                setting.maxFreqKhz / 1000,
                reason,
            )
        }
        val summaryRes = when (verification) {
            CpuVerification.VERIFIED -> R.string.cpu_verify_summary_ok
            CpuVerification.UNREADABLE -> R.string.cpu_verify_summary_unreadable
            CpuVerification.CONSTRAINED -> R.string.cpu_verify_summary_constrained
            else -> R.string.cpu_verify_summary_conflict
        }
        val summary = context.getString(summaryRes)
        // Repeated polling should not evict the first useful failure from the ring buffer.
        val key = rows.joinToString { (setting, reading) ->
            "${setting.policy}:${setting.minFreqKhz}:${setting.maxFreqKhz}:${reading.min}:${reading.max}:${reading.verify(setting)}"
        }
        if (lastVerificationKey != key) {
            if (verification == CpuVerification.CONFLICT) AppLog.w(TAG, "$summary · $detail")
            else AppLog.i(TAG, "$summary · $detail")
        } else AppLog.d(TAG, "$summary · $detail")
        lastVerificationKey = key
        _status.value = _status.value.copy(verification = verification, verificationDetail = detail,
            observedLimits = observed.joinToString { it.describe() })
        return Message(R.string.cpu_outcome_join, listOf(summary, detail))
    }

    /**
     * Governors are a kernel knob with no PowerHAL equivalent, and the node is
     * usually root-only, so this is best-effort and reported separately rather
     * than failing the whole apply.
     */
    private suspend fun applyGovernors(
        clusters: List<CpuCluster>,
        settings: List<ClusterSetting>,
    ): Message? {
        val byPolicy = clusters.associateBy { it.policy }
        val results = mutableMapOf<String, Boolean>()
        for (setting in settings) {
            val governor = setting.governor ?: continue
            val cluster = byPolicy[setting.policy] ?: continue
            val node = "${CpuScanner.CPUFREQ_ROOT}/${cluster.policy}/scaling_governor"
            val ok = Sysfs.write(node, governor)
            results[cluster.policy] = ok
            if (ok) AppLog.i(TAG, "${cluster.policy} 调速器 -> $governor")
            else AppLog.w(TAG, "${cluster.policy} 调速器写入失败: $node 不可写")
        }

        if (results.isEmpty()) return null
        val failed = results.filterValues { !it }.keys
        return when {
            failed.isEmpty() -> Message(R.string.cpu_governor_done)
            failed.size == results.size -> Message(R.string.cpu_governor_all_failed)
            else -> Message(R.string.cpu_governor_partial, listOf(failed.joinToString(", ")))
        }
    }
}
