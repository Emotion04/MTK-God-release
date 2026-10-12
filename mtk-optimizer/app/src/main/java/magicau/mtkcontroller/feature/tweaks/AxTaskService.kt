package magicau.mtkcontroller.feature.tweaks

import android.app.*
import android.content.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.privilege.PrivilegeManager

/**
 * [ok] 只在任务真的跑完时有意义：true = 服务回了 `OK:`，false = 回了 `ERROR:`。
 * 由服务在这层判定 —— 界面不该去认协议前缀。
 */
data class AxTaskState(
    val running: Boolean = false,
    val ok: Boolean? = null,
    val message: Message = Message(R.string.ax_task_idle),
)

class AxTaskService : Service() {
    companion object {
        val state = MutableStateFlow(AxTaskState())
        fun start(context: Context, kind: String, pkg: String) {
            context.startForegroundService(Intent(context, AxTaskService::class.java).putExtra("kind", kind).putExtra("pkg", pkg))
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("ax_task", getString(R.string.ax_task_channel), NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(1005, NotificationCompat.Builder(this, "ax_task").setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.ax_task_running))
            .setContentText(getString(R.string.ax_task_hint)).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true
        state.value = AxTaskState(running = true, message = Message(R.string.ax_task_working))
        scope.launch {
            val result = PrivilegeManager.axMaintenance(intent?.getStringExtra("kind").orEmpty(), intent?.getStringExtra("pkg").orEmpty())
            state.value = AxTaskState(
                running = false,
                ok = result.startsWith("OK:"),
                // 服务的回原文照录，协议前缀在这里剥掉 —— 那是传输细节，不是内容。
                message = Message(
                    R.string.raw_text,
                    listOf(result.removePrefix("OK:").removePrefix("ERROR:")),
                ),
            )
            if (result.startsWith("OK:")) AppLog.i("AxTask", result) else AppLog.w("AxTask", result)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (state.value.running) {
            state.value = AxTaskState(running = false, message = Message(R.string.ax_task_interrupted))
        }
        scope.cancel()
        super.onDestroy()
    }
}
