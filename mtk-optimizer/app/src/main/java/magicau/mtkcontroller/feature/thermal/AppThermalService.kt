package magicau.mtkcontroller.feature.thermal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import magicau.mtkcontroller.MtkApp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.power.BatteryWhitelist
import magicau.mtkcontroller.data.privilege.ForegroundApp
import magicau.mtkcontroller.data.thermal.AppThermalStore
import magicau.mtkcontroller.data.thermal.ThermalPolicyController
import kotlinx.coroutines.withContext

/**
 * 按应用切换温控策略。
 *
 * 轮询前台应用名，命中「按应用温控」表里的一条就下发对应的温控策略索引，
 * **离开就释放**（不再持有），回到系统默认。
 *
 * ## 三条设计约束（都是踩过的坑）
 *
 * 1. **不设计抢占。** 系统自己也会按前台应用选策略（`power_app_cfg.xml`：
 *    相机→12、原神→3、跑分→8）。实测系统选的会盖掉我们的，
 *    退出后系统自己回到启动默认。我们安静让位即可 ——
 *    见 `docs/thermal/facts.md` §11。两边的目标应用也不重叠：
 *    系统配的是**已经需要高性能**的那些，我们覆盖的是**其余被限制的**。
 * 2. **本软件自己要常驻才有效。** 被杀就不切。所以启动时先把自己加进
 *    电池优化白名单（[BatteryWhitelist]），这一步失败只记日志，不阻断功能。
 * 3. **轮询只在服务活着时跑**，退到后台由系统决定是否冻；
 *    频率节点读写全在 IO 上，不碰主线程。
 */
class AppThermalService : Service() {

    companion object {
        private const val CHANNEL_ID = "app_thermal"
        private const val NOTIFICATION_ID = 1010
        private const val TAG = "AppThermal"

        /** 轮询间隔。1 秒足够跟上手速，也不至于把 dumpsys 打满。 */
        private const val POLL_MS = 1_000L

        const val ACTION_START = "magicau.mtkcontroller.action.APP_THERMAL_START"
        const val ACTION_STOP = "magicau.mtkcontroller.action.APP_THERMAL_STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AppThermalService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AppThermalService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: kotlinx.coroutines.Job? = null
    private var currentPkg: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("启动中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startLoop()
        // 不 sticky：进程死了就没有句柄可释放，静默重启只会基于过期状态重新下发。
        return START_NOT_STICKY
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            val container = (application as MtkApp).container
            val store = AppThermalStore(applicationContext)

            // 先保证自己不被冻掉。失败不阻断 —— 功能照样跑，只是被杀概率高些。
            if (BatteryWhitelist.isWhitelisted() == false) {
                AppLog.i(TAG, "本软件不在电池优化白名单，尝试加入")
                BatteryWhitelist.set(true)
            }

            val rules = store.rules.first()
            if (rules.isEmpty()) {
                AppLog.w(TAG, "没有按应用温控规则，服务停止")
                stopSelf()
                return@launch
            }
            AppLog.i(TAG, "开始按应用温控，${rules.size} 条规则，间隔 ${POLL_MS}ms")

            var lastForeground = ""
            while (isActive) {
                delay(POLL_MS)
                val pkg = ForegroundApp.current()
                // 读不到时保持上一状态，不要因为一次抖动就释放掉正在用的策略。
                if (pkg.startsWith("ERROR:")) {
                    if (pkg != lastForeground) AppLog.w(TAG, pkg)
                    lastForeground = pkg
                    continue
                }
                if (pkg == lastForeground) continue
                lastForeground = pkg

                val rule = rules.firstOrNull { it.pkg == pkg }
                if (rule == null) {
                    if (currentPkg != null) {
                        applyRule(null, null)
                        AppLog.i(TAG, "$pkg 不在规则表，释放温控策略")
                    }
                    continue
                }
                // 同一条规则重复命中不重发。
                if (currentPkg == pkg) continue
                AppLog.i(TAG, "$pkg 命中规则，温控策略索引 ${rule.policyIndex}")
                applyRule(pkg, rule.policyIndex)
            }
        }
    }

    /**
     * 把当前命中的规则告诉温控档位控制器，由它决定实际下发什么。
     *
     * **这里不再自己持句柄。** 按应用温控只是温控档位的来源之一，自己 acquire 会和
     * CPU 页、实验室抢同一个资源，而释放时只放自己那一把 —— 用户可能同时被两边持有，
     * 现象是"设了却没用"，而三处界面各自显示正常。控制器保证一次只有一把句柄，
     * 并在本条规则失效时把用户选的那一档放回去。
     */
    private suspend fun applyRule(pkg: String?, index: Int?) {
        val outcome = ThermalPolicyController.applyAppRule(index)
        currentPkg = pkg?.takeIf { outcome.success }
        if (!outcome.success) AppLog.w(TAG, "按应用温控下发失败")
        updateNotification(currentPkg, index)
    }

    override fun onDestroy() {
        // 服务停下时把规则撤掉 —— 否则会留下一个没人释放的长期请求。
        // 必须 NonCancellable：下一行就 scope.cancel()，普通 launch 很可能还没开始跑，
        // 于是请求留在固件里，用户此后自己怎么设都不对。
        scope.launch {
            withContext(kotlinx.coroutines.NonCancellable) {
                ThermalPolicyController.applyAppRule(null)
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(pkg: String?, index: Int?) {
        val text = if (pkg == null || index == null) "未应用策略"
        else "$pkg，温控策略 $index"
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = android.app.PendingIntent.getActivity(
            this, 0, launch,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.app_thermal_channel_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_thermal_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

