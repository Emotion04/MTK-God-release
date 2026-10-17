package magicau.mtkcontroller.di

import android.content.Context
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.backup.BackupRepository
import magicau.mtkcontroller.data.cpu.CpuScanner
import magicau.mtkcontroller.data.cpu.CpuControl
import magicau.mtkcontroller.data.cpu.CpuControlStore
import magicau.mtkcontroller.data.lab.LabController
import magicau.mtkcontroller.data.powerhal.PerfHandlerStore
import magicau.mtkcontroller.data.powerhal.PerfLockController
import magicau.mtkcontroller.data.diag.CapabilityProbe
import magicau.mtkcontroller.data.gpu.GpuScanner
import magicau.mtkcontroller.data.gpu.GpuTuner
import magicau.mtkcontroller.data.profile.ProfileRepository
import magicau.mtkcontroller.data.settings.SettingsRepository
import magicau.mtkcontroller.data.tweaks.TweakRepository
import magicau.mtkcontroller.domain.model.ApplyMode
import magicau.mtkcontroller.feature.cpu.CpuReapplyService
import magicau.mtkcontroller.feature.thermal.AppThermalService
import magicau.mtkcontroller.feature.thermal.ThermalBrightnessService
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.powerhal.RequestOutcome
import magicau.mtkcontroller.data.thermal.ThermalPolicyController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import magicau.mtkcontroller.data.tweaks.TweakCommands

/**
 * Hand-rolled dependency container. Kept deliberately small; Hilt can replace
 * it later without touching call sites, since ViewModels take this type.
 */
class AppContainer(appContext: Context) {

    /** Exposed so services and ViewModels can start background work. */
    val context: Context = appContext.applicationContext

    val profileRepository = ProfileRepository(context)
    val settingsRepository = SettingsRepository(context)

    val cpuScanner = CpuScanner()

    /** Live PowerHAL handles, one slot per independent area of control. */
    private val perfHandlerStore = PerfHandlerStore(context)

    /** The single entry point for CPU frequency control. */
    /**
     * 用户在设置里选的频率配合项。0.3.57 起默认不带，只在显式选了某套时才附在频率请求后。
     *
     * 用 @Volatile 缓存而不是每次 apply 都读 DataStore：apply 在持锁路径上，
     * 多一次挂起读就多一个和释放竞争的窗口。下面那个收集器负责保持它新鲜。
     */
    @Volatile
    private var freqCompanionPairs: List<Pair<Int, String>> = emptyList()

    @Volatile
    private var freqRangeMode: TweakCommands.RangeMode = TweakCommands.RangeMode.RANGE
    private var freqHardLimits = false

    val cpuControl = CpuControl(
        context,
        CpuControlStore(context),
        PerfLockController(perfHandlerStore, "cpu"),
        schedCompanions = { freqCompanionPairs },
        // 用户选"只设下限"时不为真。厂商高性能场景就是这么做的，见
        // TweakCommands.RangeMode。
        rangeMode = { freqRangeMode },
        hardLimits = { freqHardLimits },
    )

    /**
     * 性能模式切换时交出/收回 CPU 频率控制。
     *
     * 厂商性能档会让平台重推自己的一套频率限制。本软件若还持着一个请求，两者叠在一起，
     * 用户此后释放时上限收不回来（PowerHAL 的合并规则只能收紧）。所以切档前先把轮询服务
     * 停掉、把请求释放；切换失败再还回来。
     *
     * 恢复原档位时**不**自动重启 —— 原来的频率设置已经丢了，应由用户重新应用，而不是
     * 让服务拿着空设置空跑。
     */
    private val onPerformanceModePause: suspend (Boolean) -> Unit = { pause ->
        if (pause) {
            CpuReapplyService.stop(context)
            val clusters = runCatching { cpuScanner.scan(deep = false) }.getOrDefault(emptyList())
            if (cpuControl.lastApplied().isNotEmpty()) cpuControl.release(clusters)
        } else {
            syncCpuReapply()
        }
    }

    val tweakRepository = TweakRepository(context, onPerformanceModePause)

    /**
     * 必须在 [tweakRepository] 之后：Kotlin 按声明顺序初始化，放前面会拿到未初始化的引用。
     */
    init {
        // 只在容器活着时收集；用户改选项时 TweakRepository 的 Flow 会推新值过来。
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            tweakRepository.freqSchedCompanion.collect { companion ->
                freqCompanionPairs = companion?.pairs.orEmpty()
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            tweakRepository.freqRangeMode.collect { freqRangeMode = it }
        }
        // **单独一个 launch。** DataStore 的 Flow 不会结束，和上面那个串在同一个
        // launch 里写成两句 collect，第二句就是死代码 —— 于是开关写进去了、
        // CpuControl 永远看不到，硬上下限静默不发。实测踩过。
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            tweakRepository.freqHardLimits.collect { freqHardLimits = it }
        }
    }

    /**
     * The canary lab gets its own handle, so an experiment can never disturb
     * the CPU limits the user is relying on.
     */
    val labController = LabController(context, PerfLockController(perfHandlerStore, "lab"))


    /**
     * 一键释放：把本 App 持有的**所有**请求撤掉，回到"没有限制"的状态。
     *
     * ## 为什么只有这一份实现
     *
     * 它原来在 `CpuViewModel.release()` 和 `HomeViewModel.release()` 各写一遍，
     * 而且**覆盖面不同** —— 首页那份不停按应用温控、不撤温控档位、不放实验室那一把。
     * 同一件事实现两次必然漂，而漂的表现是"我在首页点了释放，按应用温控还在跑"。
     * 两个入口都调这里。
     *
     * ## 覆盖面，每一样对应一个真实持有请求的地方
     *
     * | 撤什么 | 不撤会怎样 |
     * |---|---|
     * | CPU 频率请求（`PerfLockController` "cpu"） | 频率上限下不来 |
     * | 实验室请求（`PerfLockController` "lab"） | **另一个句柄槽**，`cpuControl.release()` 管不到 |
     * | 温控档位（`ThermalPolicyController`） | 档位还压在频率上 |
     * | `CpuReapplyService` | 它会把刚释放的重新放回去 |
     * | `AppThermalService` | 每 2 秒照一次前台，会把档位放回去 |
     * | `ThermalBrightnessService` | 亮度守护还在跑 |
     * | GPU 锁定 | GPU 频率还限着 |
     *
     * ## 它是补救，不是预防
     *
     * 按下去之前那些请求已经在跑了。真正防 stranded 的办法是有限时长 + 续期
     * （见 `docs/dev/architecture.md`），那一条**还没做**。这个按钮的价值在于：
     * 即使 App 被杀过、请求 stranded 了，用户重新打开 App 还能一键清回来。
     *
     * @param clusters 已扫描到的簇；空则自己扫。调用方多数已经扫过，传进来省一趟。
     */
    suspend fun releaseAllRequests(clusters: List<magicau.mtkcontroller.domain.model.CpuCluster> = emptyList()): RequestOutcome {
        val notes = mutableListOf<String>()

        // 1. CPU 频率。它的消息里带读回结果（"当前 300000-3000000"），
        //    所以它是主消息，其余只作补充。
        val scanned = clusters.ifEmpty {
            runCatching { cpuScanner.scan(deep = false) }.getOrDefault(emptyList())
        }
        val cpu = cpuControl.release(scanned)
        notes += context.getString(
            if (cpu.success) R.string.release_all_cpu_ok else R.string.release_all_cpu_failed,
        )

        // 2. 实验室。独立的 PerfLockController / 独立的 DataStore 槽。
        runCatching { labController.reset() }
            .onSuccess { notes += context.getString(R.string.release_all_lab_ok) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it
                         notes += context.getString(R.string.release_all_lab_failed, it.javaClass.simpleName) }

        // 3. 温控档位。
        runCatching { ThermalPolicyController.releaseAll() }
            .onSuccess { notes += context.getString(R.string.release_all_thermal_ok) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it
                         notes += context.getString(R.string.release_all_thermal_failed) }

        // 4. 三个会重新下发的服务。**顺序在 releaseAll 之后** ——
        //    反过来的话中间那一次轮询会把刚撤的又放回去。
        CpuReapplyService.stop(context)
        AppThermalService.stop(context)
        ThermalBrightnessService.stop(context)

        // 5. GPU。没有记录过的句柄就整段跳过，不为它白扫一次 GPU。
        val gpuHandler = runCatching { profileRepository.gpuHandler() }.getOrDefault(0)
        if (gpuHandler > 0) {
            val info = runCatching { gpuScanner.scan() }.getOrNull()
            if (info != null) {
                val gpu = gpuTuner.release(info, gpuHandler)
                if (gpu.success) profileRepository.setGpuHandler(0)
                notes += context.getString(
                    if (gpu.success) R.string.release_all_gpu_ok else R.string.release_all_gpu_failed,
                )
            } else {
                notes += context.getString(R.string.release_all_gpu_unreadable)
            }
        }

        val ok = cpu.success
        return RequestOutcome(
            success = ok,
            message = cpu.message,
            // 分隔符进资源：译文里的顿号/分号和中文不一样，焊在代码里翻不了。
            detail = context.getString(R.string.release_all_separator).let { sep ->
                notes.joinToString(sep)
            },
        )
    }

    val gpuScanner = GpuScanner()
    val gpuTuner = GpuTuner()

    val capabilityProbe = CapabilityProbe(cpuScanner, gpuScanner)

    /**
     * 首次启动的干净基线，采一次就永久保留。
     * 用户往往装上几天后才导出报告，那时设备早被改过 —— 只有这份基线还记着原样。
     */
    val baselineSnapshot by lazy {
        magicau.mtkcontroller.data.diag.BaselineSnapshot(appContext, capabilityProbe, cpuControl)
    }

    /** 用户驱动的调试录制会话。日志本身一直在落盘，会话只提高级别并标记窗口。 */
    val debugRecorder by lazy { magicau.mtkcontroller.data.log.DebugRecorder(appContext) }

    /** 组装并导出诊断包。适配报告与调试报告共用它。 */
    val reportExporter by lazy {
        magicau.mtkcontroller.data.diag.ReportExporter(
            appContext, capabilityProbe, cpuControl, baselineSnapshot,
        )
    }

    val backupRepository = BackupRepository(profileRepository, settingsRepository, tweakRepository.vivo)

    /**
     * Bring the re-apply service in line with the current mode.
     *
     * Called after a successful apply and on release. The container owns this
     * because all three ViewModels that can apply CPU limits need it, and the
     * mode/interval live in preferences rather than in any of them.
     */
    suspend fun syncCpuReapply() {
        val mode = ApplyMode.fromName(settingsRepository.applyMode.first())
        if (mode == ApplyMode.POLLING) {
            CpuReapplyService.start(context, settingsRepository.reapplyIntervalMs.first())
        } else {
            CpuReapplyService.stop(context)
        }
    }
}
