package magicau.mtkcontroller.data.power

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.privilege.PrivilegeManager

/**
 * 系统电池优化白名单（Doze whitelist）。
 *
 * 在白名单里 = 系统不会因为"长时间不用"把本软件冻掉。
 * 需要**常驻**的功能靠它 —— 目前是「按应用温控」（见 `docs/PLAN.md` 批 2）。
 *
 * 用的是 Android 原生接口，不需要 root，也不需要弹窗：
 * `dumpsys deviceidle whitelist +<pkg>` 由 uid 2000 直接改。本机实测可用。
 *
 * **不把状态存进 DataStore。** 白名单是系统事实，存下来就会和真实状态不一致
 * （`freqFloorOnly` 那个"写进去了但界面不知道"的坑就是这个形状）。每次现读。
 */
object BatteryWhitelist {

    private const val PKG = "magicau.mtkcontroller"

    /**
     * 现在在不在白名单里。
     *
     * @return true/false；null = 读不到（没授权 Shizuku 或命令失败）。
     */
    suspend fun isWhitelisted(): Boolean? = withContext(Dispatchers.IO) {
        if (!PrivilegeManager.state.value.mode.canElevate) return@withContext null
        val out = PrivilegeManager.exec("dumpsys deviceidle whitelist | grep -c $PKG")
            ?: return@withContext null
        val count = out.lineSequence().firstOrNull()?.trim()?.toIntOrNull()
            ?: return@withContext null
        count > 0
    }

    /**
     * 加入 / 移出白名单。
     *
     * @return true = 命令被系统接受。**不等于状态已变** —— 调用方要再读一次确认。
     */
    suspend fun set(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!PrivilegeManager.state.value.mode.canElevate) return@withContext false
        val sign = if (enabled) "+" else "-"
        val out = PrivilegeManager.exec("dumpsys deviceidle whitelist $sign$PKG")
            ?: return@withContext false
        out.contains("Added") || out.contains("Removed")
    }
}
