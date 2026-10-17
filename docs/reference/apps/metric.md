# Metric 1.5.3 — 固定版本实现摘录

> 分析对象：`D:\CCode\MTK\reference\sources\2026-10-b1\Metric-1.5.3.apk`
>
只记录该版本的接口、命令与来源，不是本项目的功能计划。源码摘录与推断按条目标识；本机效果需独立核验。

---

## 身份

| 项 | 值 |
|---|---|
| 名称 | Metric |
| 包名 / 模块 ID | com.itos.metric.monitor |
| 版本 | 1.5.3-release (versionCode 839) |
| 形态 | APK；三体结构：App(Compose UI) + assets/helper/metric-helper.apk 伴随 App + lib/arm64-v8a/libmetric_starter.so(自定义 app_process 可执行) 与 libmetric_daemon.so(JNI 采集)。daemon 业务逻辑是 App 自身 classes.dex 里的 Java 类 com.itos.metric.monitor.server.daemon.HiddenApiBridgeMain，与 App 同包发布同版本升级 |
| 提权路径 | 无 Shizuku。提权路径：(1) 用户在 App 内复制 shell 命令，ADB/本地终端以 shell(uid 2000) 执行 libmetric_starter.so(它是 PIE 可执行，内含 AndroidRuntime::start，等同自带 app_process)，再由它拉起 HiddenApiBridgeMain 作为独立 daemon 进程，从而以 shell 身份直读 sysfs/proc；(2) Root 自启走 /system/xbin/su；(3) 伴随 helper APK(installHelperApk 事务44)提供 GPU 频率多厂商兜底。daemon 与 App 经 ASharedMemory 零拷贝环形缓冲 + AIDL(IMetricDaemon)通信 |
| 界面技术栈 | 原生 Kotlin + Jetpack Compose(Hilt、Room、DataStore Preferences、WorkManager、Navigation)。非 Flutter，无 Dart |

## 功能清单（40 项）

### 1. App + Daemon 分离架构(总体)

**类别** 架构/提权 · **证据** [字节码] dexdump 读到 HiddenApiBridgeMain/IMetricDaemon/BinderProviderContract；[字符串] starter rodata 有 java.class.path/--nice-name=metric_server/main 类名 · **权限** shell/root 跑 starter

**做什么**：把读系统性能数据从 App 进程剥离到独立 daemon 进程；App 未连上 daemon 时显示激活页而非空仪表盘

**怎么做**：daemon=Java 类 com.itos.metric.monitor.server.daemon.HiddenApiBridgeMain，classpath 设为宿主 APK。App 侧 com.itos.metric.monitor.ipc.BinderProvider(authority com.itos.metric.monitor.binderProvider，manifest 加 android.permission.INTERACT_ACROSS_USERS_FULL)接收 daemon 回推的 IMetricDaemon binder。AIDL: IMetricDaemon,DESCRIPTOR=com.itos.metric.monitor.shared.ipc.IMetricDaemon,VERSION=18

### 2. shell 命令启动 daemon(P10 关键解法)

**类别** 架构/提权 · **证据** [字符串] dex 字符串表含该命令原文；[字节码] readelf 确认 starter PIE + AndroidRuntime::start 符号 · **权限** adb shell / 本地终端 / root

**做什么**：用户复制一条命令，以 shell 身份在设备上跑采集 daemon，绕开 App uid 读不到 scaling_min/max 的问题

**怎么做**：App 展示命令原文: APK=$(pm path com.itos.metric.monitor | sed 's/package://;q'); for f in "$(dirname "$APK")"/lib/*/libmetric_starter.so; do "$f"; break; done。libmetric_starter.so 是 PIE 可执行(interpreter /system/bin/linker64)链 AndroidRuntime::start 当 app_process 用。root 自启另走 /system/xbin/su

### 3. starter daemon 化与环境准备

**类别** 架构/提权 · **证据** [字符串] starter .rodata 原文(路径/格式串/属性名) · **权限** shell/root

**做什么**：starter 启动 daemon 前 fork/setsid 守护、挂 debugfs、按小核 cpuset 设亲和、清过期日志、探测 ROM

**怎么做**：libc: fork/setsid/chdir('/')/dup2/popen；mount /sys/kernel/debug(DebugFS 供 ftrace/perf)；读 /dev/cpuset/{restricted,system-background,background}/cpus、/sys/fs/cgroup/cpuset/*/cpus 定小核并 sched_setaffinity；日志落 /data/local/tmp/metric_logs/%04d-%02d-%02d%s.log，格式 %02d:%02d:%02d.%03ld [%s] [Starter] [tid=%d] %s；探测 ro.vivo.default.version/ro.iqoo.os.build.display.id 等 vivo/iqoo 属性

### 4. JNI 采集桥 DaemonNativeBridge(12 方法)

**类别** 架构/采集 · **证据** [字符串] daemon.so 内明文 JNI 符号；[字节码] readelf dyn-syms 确认 NDK binder/ASharedMemory/syscall/eventfd/sched/clock_gettime · **权限** daemon 身份

**做什么**：Java daemon 与原生 libmetric_daemon.so 的边界，原生真正读硬件/建共享内存/收发 binder

**怎么做**：JNI 符号 Java_com_itos_metric_monitor_server_daemon_DaemonNativeBridge_*: nativeCreateDaemonBinder、nativeReadSharedMemoryLongAcquire、nativePublishRuntimeFeatureSnapshot、nativeUpdateBatterySnapshot、nativeUpdateTaskFpsSample、nativeClearTaskFpsSample、nativeUpdateProcessRecordMemorySnapshot、nativeSetForegroundAppTarget、nativeSetForegroundAppMonitorAvailable、nativeSetHelperBinder、nativeSetSurfaceFlingerBinder、nativeSetAppInstallSnapshot。原生用 ASharedMemory_create/mmap、eventfd、sched_*、syscall(perf_event_open)、clock_gettime、AParcel/AIBinder_*

### 5. IMetricDaemon AIDL 契约(事务码全表)

**类别** IPC · **证据** [字节码] dexdump IMetricDaemon$Stub 静态常量逐条读出 · **权限** 调用方需能连 provider(shell/root/同 App)

**做什么**：App↔daemon 全部能力集合：读指标、启停会话、文件读写、杀进程、装 helper、zram

**怎么做**：Stub.TRANSACTION_*: 2 getVersion,3 stopService,4 updateConfig,5 restartService,6 shouldPromptInstallHelper,7 exportLogs,8 getDeviceSerialNumber,9 getActivationMethod,10 startBasicMetrics,11 stopBasicMetrics,12 openBasicMetricRegion,13/14 reg/unregBasicMetricCallback,15 getGovernorParams,16 getMemoryInfoRaw,17 getZramCompAlgorithmRaw,18 applyZramSettings,19 startProcessMetrics,20 stopProcessMetrics,21 openProcessMetricRegion,22/23 reg/unregProcessMetric,24 getProcessDetail,25 killProcess,26 forceStopPackage,27 setThreadMetricTargetPid,28 startThreadMetrics,29 stopThreadMetrics,30 openThreadMetricRegion,31/32 reg/unregThreadMetric,33/34 reg/unregForegroundApp,35 isForegroundAppMonitorAvailable,36 getForegroundAppTarget,37/38 reg/unregRealtimeFps,39 startFpsRecordSession(long),40 stopFpsRecordSession,41 openFpsRecordRegion,42/43 reg/unregFpsRecord,44 installHelperApk(ParcelFileDescriptor),45 readFile(String),47 writeFile(String,String),48 listFiles(String),49 startProcessRecordSession(int),50 stopProcessRecordSession,51 openProcessRecordRegion,52/53 reg/unregProcessRecord,54 openProcessRecordTrace(long),55 grantNoticeAccessibility,56 setNoticeAccessibilityGuardEnabled(Z,Z),57 openFpsRecordTrace(long),58 getGpuFrequencySource,59 drainThreadAllocationActivityCounts

### 6. 基础指标 basic metric(CPU/GPU/内存/温度/功率卡)

**类别** 监控 · **证据** [字符串] overlays.md 列负载监视器字段；[字节码] 事务码与回调接口 · **权限** daemon 身份

**做什么**：首页总览：CPU 占用与频率、GPU 占用与频率、RAM/DDR、FPS、Power、电池温度

**怎么做**：startBasicMetrics(10)→registerBasicMetricCallback(13) 走 binder 回调 IIpcBasicMetricCallback；高频样本经 openBasicMetricRegion(12) 返回 ASharedMemory FD，App 侧 nativeReadSharedMemoryLongAcquire 零拷贝读环形缓冲

### 7. CPU 每簇频率(读回 P10 核心)

**类别** 监控 · **证据** [字符串] dex 路径/后缀；[字节码] getGovernorParams 事务 · **权限** shell/root(这正是它优于 App uid 的原因)

**做什么**：按簇读 scaling_min/max/cur、governor、可用频率表；即用户 App P10 读不出、Metric 作为 shell 能读的那组节点

**怎么做**：节点(字符串原文): /sys/devices/system/cpu/cpufreq/policy、/sys/devices/system/cpu/cpufreq/mp-cpufreq/cluster(MTK 多 policy)、/sys/devices/system/cpu/online；后缀 /scaling_min_freq、/scaling_max_freq、/cpufreq/scaling_governor、/scaling_governor、/scaling_available_governors、/scaling_available_frequencies、/boost_freq、/scaling_boost_frequencies、/max_freq、/min_freq、/cpufreq/related_cpus、/available_frequencies。getGovernorParams(15) 读 governor 可调参数

### 8. CPU 占用与每核负载(normalizedCpuUsage)

**类别** 监控 · **证据** [字符串] normalizedCpuUsageEnabled/cpuCoreBlob/startTimeTicks；[字节码] 库表结构 · **权限** daemon

**做什么**：每核占用百分比，支持归一化；进程/线程 CPU%

**怎么做**：dex 有 normalizedCpuUsageEnabled(按 max_freq 归一公平比大小核)；库表 fps_record_timeline.cpuCoreBlob 存每核样本、process_record_timeline.systemCpuPercent。进程 CPU 来自 /proc/<pid>/stat 的 utime/stime 差(process_record_sessions.startTimeTicks)

### 9. CPU 硬件计数器 cycles/instructions

**类别** 监控 · **证据** [字符串] dex 字段名 cpuCycles/cpuInstructions/TenthM；[字节码] daemon.so 链 syscall(perf_event_open 无 libc 封装只能走 syscall) · **权限** daemon(perf_event_paranoid 可能限制，需 shell/root)

**做什么**：记录 CPU 周期与指令数(TenthM 归一)用于精确占用/效能分析

**怎么做**：libmetric_daemon.so 经 syscall()=perf_event_open 读 PERF_COUNT_HW_CPU_CYCLES/INSTRUCTIONS_RETIRED；落地字段 cpuCycles、cpuInstructions、cpuCyclesTenthM、cpuInstructionsTenthM

### 10. GPU 频率(多厂商)

**类别** 监控 · **证据** [字符串] daemon.so 与 helper dex 路径清单；[字节码] GpuSampler.kt/MtkGpufreqV1/V2 类名 · **权限** shell/root 或 helper

**做什么**：读当前/最大/最小 GPU 频率，MTK/Adreno/Mali

**怎么做**：daemon 侧: /sys/class/devfreq/gpufreq、/proc/gpufreq/gpufreq_opp_dump、/proc/gpufreqv2/gpu_working_opp_table、/proc/gpufreqv2/stack_working_opp_table、/sys/class/kgsl/kgsl-3d0/{devfreq,gpu_available_frequencies,max_clock_mhz,min_clock_mhz}。helper 兜底另试 /sys/kernel/ged/hal/current_freqency、/sys/class/misc/mali0/device/clock、/sys/devices/*mali*/clock、/sys/devices/.../kgsl-3d0*/gpuclk、PowerVR /sys/devices/platform/omap/pvrsrvkm.0/sgxfreq/frequency、Tegra /sys/kernel/tegra_gpu/gpu_rate。getGpuFrequencySource(58) 返回当前路径

### 11. GPU 占用率(GED loading)

**类别** 监控 · **证据** [字符串] ged 节点清单+gpuLoadPercent 字段 · **权限** shell/root

**做什么**：MTK GPU 负载百分比(基于 GPU Engine Daemon loading-based DVFS)

**怎么做**：读 /sys/kernel/ged/hal/loading_base_dvfs_step、/sys/kernel/ged/hal/force_loading_base(加载档位即负载)；关联 /sys/module/ged/parameters/{g_fb_dvfs_threshold,ged_smart_boost,is_GED_KPI_enabled}、/sys/kernel/ged/hal/{dvfs_margin_value,timer_base_dvfs_margin}

### 12. DDR/DRAM 频率与温度

**类别** 监控 · **证据** [字符串] dex 路径 · **权限** shell/root

**做什么**：记录 DDR 频率(MTK 走 DVFSRC)

**怎么做**：读 /sys/class/devfreq/mtk-dvfsrc-devfreq/{available_frequencies,dvfsrc_opp_table,min_freq,dvfsrc_force_vcore_dvfs_opp}；也读 /sys/devices/system/cpu/bus_dcvs/。落地 ddrFrequency、ddrTemperatureCelsius

### 13. 温度(CPU/GPU/DDR/电池)

**类别** 监控 · **证据** [字符串] getZone、各 *TemperatureCelsius 字段、BATTERY_CHANGED · **权限** shell/root(thermal zone 通常可，个别需特权)

**做什么**：多路温度，用于悬浮窗与 FPS 记录时间线

**怎么做**：按 thermal zone type 匹配: getZone+/sys/class/thermal/thermal_zone<N>/type 与 temp；落地 cpuTemperatureCelsius/gpuTemperatureCelsius/ddrTemperatureCelsius/batteryTemperatureCelsius。电池温度同时来自 BatteryManager(BATTERY_CHANGED)

### 14. 功率/电流/电压(含校准、双电芯)

**类别** 监控 · **证据** [字符串] advice_bat_*、BatteryCalibrationSnapshot/serialDualCellEnabled；[字节码] IpcServerRuntimeSettings 字段 · **权限** shell/root

**做什么**：实时功率 W，支持电流倍率校准与串联双电芯修正；自动探测电流倍率

**怎么做**：节点: advice_bat_avg_current、advice_bat_max_current(MTK 电池电流)；BatteryManager 取 voltage/current。校准: BatteryCalibrationSnapshot(firstRawPower=)、currentCalibrationValue+serialDualCellEnabled(经 updateConfig 事务4 下发 IpcServerRuntimeSettings)。自动探测=拔充电器放电态采集稳定样本推倍率。落地 powerW、averagePowerW

### 15. 内存详情(Swap/Zram/VM/MemInfo)

**类别** 监控 · **证据** [字符串] 路径与 meminfo 字段名 · **权限** shell/root(部分 zram 需 root)

**做什么**：Swap 容量占用、Zram 压缩率/算法、VM 参数、/proc/meminfo 关键字段

**怎么做**：getMemoryInfoRaw(16) 返回 String[](直接 /proc/meminfo 字段 MemTotal/MemFree/MemAvailable/Cached/SwapTotal/SwapFree/AnonPages/Mapped/Shmem/Slab/KernelStack/PageTables/VmallocUsed/ZramCompr)；/proc/sys/vm/swappiness、/proc/sys/vm/watermark_scale_factor；zramMmStat、getZramCompAlgorithmRaw(17)、enabledSwaps、mainSwap

### 16. 实时帧率 FPS(SurfaceFlingerLatency)

**类别** 监控/FPS · **证据** [字节码] 库表默认 'SurfaceFlingerLatency'、IpcRealtimeFpsSnapshot 字段；[字符串] filterAbnormalFps、nativeSetSurfaceFlingerBinder · **权限** daemon(SF 访问)

**做什么**：当前前台应用实时 FPS，是负载/悬浮/FPS 记录的数据核心

**怎么做**：默认算法 'SurfaceFlingerLatency'(fps_record_sessions.realtimeFpsAlgorithm 默认值)。nativeSetSurfaceFlingerBinder 把 SurfaceFlinger binder 推到原生侧取图层帧呈现时间算 FPS；实时回调 IIpcRealtimeFpsCallback→IpcRealtimeFpsSnapshot(fps:float,hasFps:bool,preciseSamplingEnabled,sampleTimeMillis)。preciseSampling 提高采样率。filterAbnormalFps 用 maxSupportedRefreshRate 阈值剔除异常帧率。realtimeFpsAlgorithmOrdinal 可切算法

### 17. 显示插帧态 MEMC/超分/HDR/ADFR 采样

**类别** 监控/FPS · **证据** [字符串] 正则/属性/表；[字节码] CREATE TABLE、getMemcState/getAdfrState、nativePublishRuntimeFeatureSnapshot · **权限** daemon+可读 dumpsys/属性

**做什么**：记录 FPS 会话期间是否开 MEMC 插帧/超分/HDR/ADFR，并区分源帧与插入帧以算真实 FPS

**怎么做**：表 fps_record_runtime_features(sessionId,eventTimeMillis,memcState,memcSourceFrameCount,memcInsertedFrameCount,superResolutionState,hdrState,adfrState)。ADFR 用正则 ^\s*OPlus ADFR Mode\s*:\s*(-?\d+) 从 dumpsys 解析；OPPO MEMC: OPlusFeatureMEMCGame_PW、POLICY_MEMC、/data/user/0/com.oplus.games/files/mmkv/com.oplus.super_resolution_prefs；ADFR 服务 oplus_vrr_service。getMemcState/getAdfrState 返回 RuntimeFeatureState；DisplayDumpEvidence(memc=)、surfaceFlingerDumpAvailable。nativePublishRuntimeFeatureSnapshot 推原生

### 18. FPS 记录会话+时间线+Jank

**类别** FPS记录 · **证据** [字节码] CREATE TABLE fps_record_*、事务码 · **权限** daemon

**做什么**：按会话记录一次前台应用帧率、帧时间、Jank、CPU/GPU/DDR/Power/温度快照

**怎么做**：startFpsRecordSession(long durationMs)(39)→IpcFpsRecordStartResult；stopFpsRecordSession(40)；会话绑定当前前台 app，前台变化即停并保存。表 fps_record_sessions(stopReason/isActive/averageFps/averagePowerW/traceState/cropResolution/realtimeFpsAlgorithm/preciseSamplingEnabled)；fps_record_timeline 存 fps/frameTimesBlob/maxFrameTimeMillis/smallJankCount/jankCount/bigJankCount/jankTimeNanos/cpuLoadPercent/cpuTemperatureCelsius/gpuLoadPercent/gpuFrequencyMhz/gpuTemperatureCelsius/ddrFrequency/ddrTemperatureCelsius/powerW/batteryTemperatureCelsius/batteryCapacityPercent。1%Low/5%Low 在详情页统计

### 19. FPS 记录——线程负载明细

**类别** FPS记录 · **证据** [字节码] CREATE TABLE+查询字符串 · **权限** daemon(读 /proc/<pid>/task/<tid>/stat+sched_getaffinity)

**做什么**：记录会话内每线程 CPU 负载、主要核心、迁移次数、Affinity 变化、核心分布

**怎么做**：表 fps_record_thread_load_prepared(tid,comm,averageLoad,maxLoad,dominantCore,dominantCorePercent,migrationCount,firstAffinity,affinityChangeCount,loadPointBlob,corePointBlob,coreDistributionBlob,affinitySegmentBlob)

### 20. FPS/Process 记录 Perfetto trace 抓取与导出

**类别** FPS记录 · **证据** [字符串] .perfetto-trace、exportTrace；[字节码] 事务 54/57、traceState · **权限** daemon

**做什么**：可选对会话抓 Perfetto trace、长截图、导出记录

**怎么做**：openFpsRecordTrace(long)(57)、openProcessRecordTrace(long)(54) 返回 ParcelFileDescriptor；.perfetto-trace/.perfetto-trace.tmp 文件；traceState/traceSizeBytes 字段。stopReason 记录手动/计时/前台变化/daemon 断开/目标丢失/后台被杀

### 21. 悬浮窗——负载监视器

**类别** 悬浮窗 · **证据** [字符串] applyLoadBlurRadius/canDrawOverlays/MANAGE_OVERLAY_PERMISSION；[文档] overlays.md · **权限** 悬浮窗权限+daemon

**做什么**：在其他 App 上层浮动显示 CPU/GPU/RAM/FPS/Power/电池温度

**怎么做**：需 SYSTEM_ALERT_WINDOW；Settings.ACTION_MANAGE_OVERLAY_PERMISSION+canDrawOverlays 校验；WindowManager.addView；背景模糊 overlayBackgroundBlurEnabled/applyLoadBlurRadius/capsuleGlassOverlayAlpha(玻璃拟态)。需 daemon 在线

### 22. 悬浮窗——线程/进程/温度/FPS 监视器

**类别** 悬浮窗 · **证据** [字符串] apply*BlurRadius、FpsOverlayController、dismiss 日志 · **权限** 悬浮窗权限+daemon

**做什么**：线程监视器(CPU%/CPUS/TID/COMM)、进程监视器、温度监视器、FPS 记录器悬浮入口

**怎么做**：同类 overlay；applyThreadBlurRadius/applyProcessBlurRadius/applyTemperatureBlurRadius；FpsOverlayController+FpsOverlayContentState(snapshot=)；start/stopThreadMetrics(28/29)、setThreadMetricTargetPid(27)

### 23. 前台应用跟踪

**类别** 监控 · **证据** [字节码] IActivityTaskManager 类型、HiddenApiForegroundAppProvider、IpcForegroundAppTarget；[字符串] ActivityTask service 日志 · **权限** daemon(隐藏 API)

**做什么**：识别当前前台 app(是否 Metric 自身)，FPS 记录绑定它，前台变化即停

**怎么做**：daemon 侧 HiddenApiForegroundAppProvider 通过 hidden API android.app.IActivityTaskManager$Stub$AsInterface(ServiceManager getService activity_task)取 top resumed activity；辅以 getRunningAppProcesses。setForegroundAppTarget/setForegroundAppMonitorAvailable(JNI)，isForegroundAppMonitorAvailable(35)、getForegroundAppTarget(36)→IpcForegroundAppTarget(pid=)；回调 IIpcForegroundAppCallback；另有 AccessibilityService(TYPE_WINDOW_STATE_CHANGED)

### 24. 进程详情/杀进程/强停

**类别** 进程管理 · **证据** [字节码] 事务 24/25/26、process_record_timeline 字段；[字符串] /cmdline /comm /proc/self/status · **权限** daemon(强停/杀需特权)

**做什么**：看进程 CPU%/RSS/线程数，杀进程、强停包

**怎么做**：getProcessDetail(int pid)(24) 读 /proc/<pid>/{cmdline,comm,status,statm,smaps}(Uid:/Threads:/VmRSS；pss/uss/privateDirty 需 smaps)。killProcess(int)(25)、forceStopPackage(String,int)(26)。进程内存快照 nativeUpdateProcessRecordMemorySnapshot(PSS/USS/privateClean/privateDirty/sharedClean/sharedDirty/rssAnon/rssFile/rssShmem/swapBytes)

### 25. 进程记录会话(单进程长时间画像)

**类别** 进程管理 · **证据** [字节码] CREATE TABLE process_record_* · **权限** daemon

**做什么**：对指定进程持续记录 CPU%/RSS/线程/温度/PSS/USS，带图表详情

**怎么做**：startProcessRecordSession(int pid)(49)、stop(50)、openProcessMetricRegion(21)、openProcessRecordRegion(51)、openProcessRecordTrace(54)。表 process_record_sessions(processName,command,commandLine,pid,uid,startTimeTicks,averageCpuPercent,peakCpuPercent,totalCpuTimeMillis,peakRssBytes,rssDeltaBytes,peakThreadCount,traceState)；process_record_timeline(processUserCpuTicks/SystemCpuTicks/CpuPercent/systemCpuPercent/rss*/swapBytes/threadCount/enhancedMemorySampled/pss*/uss*/private*/shared*/cpuCoreBlob/cpuClusterBlob)

### 26. 线程指标监视(ThreadAllocation 分析)

**类别** 监控 · **证据** [字节码] IpcServerRuntimeSettings 字段、事务 59、ThreadAllocationRoute；[字符串] threadAllocation* · **权限** daemon

**做什么**：线程负载/落核/迁移/Affinity 分析，支持游戏/实时/权重模式与黑名单

**怎么做**：openThreadMetricRegion(30)、IIpcThreadMetricCallback；IpcServerRuntimeSettings.threadAllocation*: ModeOrdinal/AppEnabled/GameEnabled/GameAdaptiveEnabled/RealtimeEnabled/WeightEnabled/BlacklistPackages/GamePackages；drainThreadAllocationActivityCounts(59)→IpcDailyActivityCount[]。ThreadAllocationRoute 导航

### 27. 实时通知+辅助服务守护

**类别** 通知/保活 · **证据** [字节码] manifest 组件、事务 55/56；[字符串] MetricRealtimeNotificationKeepAlive/grantNoticeAccessibility · **权限** 无障碍+通知

**做什么**：以通知常驻显示实时性能，并借无障碍服务守护防被杀

**怎么做**：前台服务 RealtimeNotificationService(FGS SPECIAL_USE，stopWithTask=false)，NotificationChannel MetricRealtimeNotificationKeepAlive；RealtimeNotificationAccessibilityService(BIND_ACCESSIBILITY_SERVICE)；daemon 侧 grantNoticeAccessibility(55)、setNoticeAccessibilityGuardEnabled(Z,Z)(56)；RealtimeNotificationOverlayReceiver 受 android.permission.INTERACT_ACROSS_USERS_FULL 保护

### 28. FPS 快捷设置磁贴(QS Tile)

**类别** 入口 · **证据** [字节码] manifest service 定义与 property · **权限** 无(前台服务特殊用途需声明)

**做什么**：从快捷设置一键开始/停止前台应用帧率持续记录

**怎么做**：FpsRecordTileService(BIND_QUICK_SETTINGS_TILE，TOGGLEABLE_TILE/ACTIVE_TILE)+FpsRecordService(前台服务，FGS type=0x40000000 SPECIAL_USE，property PROPERTY_SPECIAL_USE_FGS_SUBTYPE='用户主动通过快捷设置磁贴启动的前台应用帧率持续记录')

### 29. 进程记录服务+入口

**类别** 进程管理 · **证据** [字节码] manifest · **权限** 无

**做什么**：独立前台服务承载进程记录

**怎么做**：ProcessRecordService(com.itos.metric.monitor.core.service，FGS SPECIAL_USE)，intent action com.itos.metric.monitor.action.OPEN_PROCESS_RECORD 直达

### 30. PerformanceTuning 调参 hub(ColorOS/MTK/骁龙/Governor)

**类别** 调参(Pro) · **证据** [字符串] *Route、ged/magt/oplus 节点；[字节码] writeFile 事务、IpcServerRuntimeSettings.membershipActive · **权限** shell/root 写节点

**做什么**：按厂商/维度调 CPU/GPU/调度参数(很可能是 Metric Pro 付费能力)

**怎么做**：导航路由: PerformanceTuningRoute、ColorOsTuningRoute、MtkTuningRoute(MtkTuningScreen)、SnapdragonTuningRoute、GovernorParamsRoute。底层写用 IMetricDaemon.writeFile(47) 通用写 sysfs+getGovernorParams(15)/applyZramSettings(18)。MTK 引用: /sys/module/mtk_perf_ioctl_magt/parameters(MTK MAGT)、/proc/game_opt/disable_cpufreq_limit、/sys/kernel/ged/hal/custom_boost_gpu_freq、/sys/module/ged/parameters/ged_smart_boost。OPPO: ADFR/OPlusFeatureMEMC。membershipActive 门控

### 31. Zram 设置(重建/改算法/swappiness)

**类别** 调参 · **证据** [字节码] 事务 17/18；[字符串] applyZramSettings、zramMmStat、swappiness · **权限** shell/root(改 zram)

**做什么**：调整 Zram 大小/算法、swappiness，实时显示压缩状态

**怎么做**：applyZramSettings(int,String)(18) 经 daemon 下发；读 /proc/sys/vm/swappiness、zramMmStat、getZramCompAlgorithmRaw(17)、enabledSwaps/mainSwap/zramAlgorithm/currentZramSizeMb。App 侧含启停会话

### 32. App 管理/组件快捷方式

**类别** 工具 · **证据** [字节码] manifest 组件；[字符串] *Route · **权限** QUERY_ALL_PACKAGES

**做什么**：应用列表/详情、按组件(Activity/Service/Receiver)创建桌面快捷方式直接启动

**怎么做**：ApplicationListRoute/ApplicationDetailRoute/ComponentManagerRoute；ComponentShortcutActivity(taskAffinity 空,excludeFromRecents)+ComponentShortcutPinnedReceiver；依赖 QUERY_ALL_PACKAGES

### 33. 文件浏览/读(远程 sysfs 浏览器)

**类别** 工具 · **证据** [字节码] 事务 45/47/48+IpcFileListResult/IpcFileReadResult/IpcFileEntry · **权限** daemon 权限

**做什么**：App 可让 daemon 列目录/读任意文件(浏览 sysfs/proc 或取回 daemon 侧文件)

**怎么做**：listFiles(String)(48)→IpcFileListResult/IpcFileEntry、readFile(String)(45)→IpcFileReadResult、writeFile(String,String)(47)、exportLogs(7)→ParcelFileDescriptor

### 34. helper APK 安装/管理

**类别** 提权 · **证据** [字符串] helper manifest；[字节码] 事务 6/44、nativeSetHelperBinder、GpuSampler.kt · **权限** REQUEST_INSTALL_PACKAGES/shell pm

**做什么**：App 可让 daemon 安装伴随 helper APK(提供 GPU 频率兜底)

**怎么做**：installHelperApk(ParcelFileDescriptor)(44)；shouldPromptInstallHelper(6)；helper 内嵌 assets/helper/metric-helper.apk(com.itos.metric.helper v1.1,targetSdk 25,HelperBinderProvider authority com.itos.metric.helper.binderProvider,HelperMainActivity)；nativeSetHelperBinder 回连

### 35. 引导/激活页+电池功率校准向导

**类别** UI · **证据** [字符串] 离线文档；[字节码] BinderProviderContract · **权限** 无(引导)

**做什么**：三页引导(介绍/启动服务/校准)；daemon 未连时显示激活页，不假装连接

**怎么做**：启动服务页给 Root 启动(/system/xbin/su)或复制 shell 命令；校准页自动探测电流倍率(放电态)、串联双电芯开关、手动电流校准；激活态判定=App 实际收到 daemon setBinder。见 assets/offline_docs/{getting-started,startup-guide,calibration}.md

### 36. 设置持久化(DataStore)

**类别** UI · **证据** [字符串] update_channel/fetchUpdate；[字节码] IpcServerRuntimeSettings、AppDownloader receiver · **权限** 无(root 自启需 su)

**做什么**：主题、开机自启(root)、更新通道、电池校准、FPS 采样、日志级别/保留天数、Metric Pro

**怎么做**：Jetpack DataStore Preferences(.preferences_pb)，多文件(communityDataStore/session configs/session data)。日志落 /data/local/tmp/metric_logs，logLevelOrdinal/logRetentionDays 经 updateConfig(4) 同步 daemon。更新: update_channel 设置+fetchUpdate 拉清单、release-assets.githubusercontent.com/jsdelivr 代理下载 .apk；AppDownloader$DownloadCompleteReceiver(SEND_DOWNLOAD_COMPLETED_INTENTS)

### 37. 云备份/恢复+会员

**类别** 其他 · **证据** [字符串] cloudbase URL、*Backup；[字节码] DailyActivityWorker、FpsRecordBackupRoute、事务 8/9 · **权限** INTERNET/网络

**做什么**：FPS/进程记录云端与 WebDAV 备份恢复；Metric Pro 会员门控；每日活跃上报

**怎么做**：后端 https://cloudbase.itostar.com.cn/{backup,backup/list,epay/create-order,epay/query,user/status,user/activity,user/upsert}；FpsRecordBackupRoute、uploadingWebDavBackup/uploadingOfficialBackup；DailyActivityWorker(WorkManager)上报 IpcDailyActivityCount；getDeviceSerialNumber(8)/getActivationMethod(9)

### 38. 反调试/防篡改

**类别** 安全 · **证据** [字符串] frida/su/superuser/xposedmodule、isRooted · **权限** 无

**做什么**：检测 Frida/Xposed/Root，防被 hook

**怎么做**：扫描 /data/local/tmp/{frida-server,frida-agent-32.so,frida-agent-64.so,re.frida.server}、/system/xbin/su、/system/app/Superuser.apk；检查 APK meta xposedmodule/xposedminversion；isRooted/isRootServer 标记

### 39. 开机自启(root)

**类别** 启动 · **证据** [字节码] manifest BootCompletedReceiver；[字符串] settings.md · **权限** root

**做什么**：root 设备开机后自动以 su 启动 daemon

**怎么做**：BootCompletedReceiver(BOOT_COMPLETED,exported=false)+设置『Boot auto-start(ROOT)』；经 su 执行 starter。非 root 无效

### 40. FPS 记录数据库维护/清理

**类别** FPS记录 · **证据** [字节码] Room _Impl、迁移 SQL 字符串 · **权限** 无

**做什么**：后台清理过期记录、迁移 schema

**怎么做**：FpsRecordDatabaseMaintenanceWorker(WorkManager)；Room 库 com.itos.metric.monitor.core.database.FpsRecordDatabase/ProcessRecordDatabase，多版本迁移(ALTER TABLE fps_record_timeline ADD COLUMN smallJankCount/jankTimeNanos；新增 fps_record_thread_load_prepared、fps_record_runtime_features)

## 明确查不出的东西（7 条）

- SurfaceFlinger 读帧的具体机制(是 ISurfaceComposer/FrameTimeline 事务还是 dumpsys SurfaceFlinger --latency 解析)与其事务码无法确定：逻辑在 libmetric_daemon.so 原生 stripped 代码里，本机无反汇编 aarch64 能力；只有 nativeSetSurfaceFlingerBinder、'SurfaceFlingerLatency'、surfaceFlingerDumpAvailable、sourceFrames/insertedFrames 等线索。[未确定]
- per-core CPU 负载百分比(cpuLoadPercent)到底是 /proc/stat 每核 idle/total 差算还是 perf 计数器算未能最终确认：dex 无明确 '/proc/stat' 字面量，但 daemon.so 链了 syscall(perf_event_open)+clock_gettime，且有 cpuCycles/cpuInstructions。cpuLoadPercent 精确来源 [未确定]。
- getGovernorParams(事务15)读 governor 可调参数的确切路径(是否遍历 /sys/devices/system/cpu/cpufreq/policy<N>/<governor>/ 目录)未反汇编确认。[未确定]
- PerformanceTuning hub 下 ColorOsTuning/MtkTuning/SnapdragonTuning/GovernorParams 各屏具体写哪些节点、下发哪些值：底层只有通用 writeFile(47)/applyZramSettings(18)/governor 读接口，具体预设与写入目标在混淆类里未逐条确认。[未确定]
- helper 读 GPU 频率为何比 daemon 强：helper 无特殊权限、targetSdk 25、普通 App，判断并非更高权限只是更多兜底路径；是否依赖被装成系统 App [未确定]。
- ThreadAllocation 到底是只读分析线程落核还是也下发 sched_setaffinity/写 cpuset 改变落核：监控侧确定(迁移/affinity 段)，写回侧 [未确定]。
- libmetric_daemon.so stripped、字符串极少(约 232 条，几乎全是 JNI 符号与 NDK binder 导入)，核心常量(perf event type/config、SF 事务码、采样周期数值)以二进制存在，只读提取不出。[未确定]
