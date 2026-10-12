package magicau.mtkcontroller.di

import android.content.Context
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

    val cpuControl = CpuControl(
        context,
        CpuControlStore(context),
        PerfLockController(perfHandlerStore, "cpu"),
        schedCompanions = { freqCompanionPairs },
        // 用户选"只设下限"时不为真。厂商高性能场景就是这么做的，见
        // TweakCommands.RangeMode。
        rangeMode = { freqRangeMode },
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
    }

    /**
     * The canary lab gets its own handle, so an experiment can never disturb
     * the CPU limits the user is relying on.
     */
    val labController = LabController(context, PerfLockController(perfHandlerStore, "lab"))

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
