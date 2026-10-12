package magicau.mtkcontroller

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.log.CrashLog
import magicau.mtkcontroller.data.log.LogFileStore
import magicau.mtkcontroller.data.log.LogLevel
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import magicau.mtkcontroller.di.AppContainer

/**
 * Application entry point.
 *
 * The Shizuku binder can arrive at any time, so the privilege manager starts
 * listening here rather than in an Activity.
 */
class MtkApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 日志落盘与崩溃捕获必须最先装上：后面任何一步出问题，都要能在磁盘上留下痕迹。
        AppLog.attach(this)
        CrashLog.install()
        PrivilegeManager.init(this)
        appScope.launch { container.settingsRepository.migrateIfNeeded() }
        appScope.launch {
            container.settingsRepository.logLevel.collect { AppLog.setLevel(LogLevel.fromName(it)) }
        }
        // Restore the CPU control bookkeeping before any screen can act on it.
        appScope.launch { container.cpuControl.load() }
        AppLog.i(
            "App",
            getString(
                R.string.app_startup,
                getString(R.string.app_name),
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
        )
        // 首次启动的代表性状态只采一次，之后不再覆盖 —— 见 BaselineSnapshot 的说明。
        appScope.launch { container.baselineSnapshot.captureIfAbsent() }
        appScope.launch { LogFileStore.trim(this@MtkApp) }
        // 上次若在录制中被杀，日志已在磁盘上；把会话标出来，让用户能导出。
        appScope.launch { container.debugRecorder.recoverInterrupted() }
    }
}
