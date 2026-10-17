package magicau.mtkcontroller.data.power

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.privilege.PrivilegeManager

/**
 * 系统电池优化白名单（Doze whitelist）。
 *
 * 在白名单里 = 系统不会因为"长时间不用"把本软件冻掉。
 * 需要**常驻**的功能靠它 —— 目前是「按应用温控」。
 *
 * 走 `dumpsys deviceidle whitelist +<pkg>`，由 uid 2000（Shizuku）执行，
 * 不需要 root，也不弹系统对话框。
 *
 * **实测可用**（2026-10-14，本机）：`+` 之后回读列表里就有，`-` 能移出。
 * 记录在 `docs/device/commands.md` 的「电池优化白名单」一节。
 * 这条以前注释里写着"实测可用"却从没留过证据，2026-10-13 复查时因此被误判成
 * 不可用，白折腾一轮 —— **结论要连带证据一起落盘**，否则下次还要重新怀疑。
 *
 * **但厂商 ROM 的后台管理不止这一项**，所以界面仍要给一个跳到系统原生授权
 * 弹窗的入口，见 [openSystemSettings]。
 *
 * **不把状态存进 DataStore。** 白名单是系统事实，存下来就会和真实状态不一致
 * （`freqFloorOnly` 那个"写进去了但界面不知道"的坑就是这个形状）。每次现读。
 */
object BatteryWhitelist {

    private const val PKG = "magicau.mtkcontroller"

    /**
     * 打开系统自带的电池优化设置页，让用户自己去看/去改。
     *
     * **为什么在能直接改之外还要给这个入口。** 直写只覆盖 Doze 白名单这一项，
     * 而厂商 ROM 的后台管理远不止一项（本机是 vivo，直写成功也可能被别的策略冻）。
     * 系统那一页能看到全貌，用户也能自己确认我们到底改成功了没有。
     *
     * **首选安卓原生的那个授权弹窗**，不是厂商 ROM 的后台管理页。
     * 本机（V2430A / vivo）上 `cmd package resolve-activity` 实测（2026-10-13）：
     *
     * | action | 解析到 |
     * |---|---|
     * | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` | `com.android.settings/.fuelgauge.RequestIgnoreBatteryOptimizations` |
     * | `IGNORE_BATTERY_OPTIMIZATION_SETTINGS` | `com.iqoo.powersaving/.BackgroundHighUsageActivity` |
     * | `APPLICATION_DETAILS_SETTINGS` + `package:` | `com.android.settings/.applications.InstalledAppDetails` |
     *
     * 第一条才是「安卓的电池优化原生权限界面」：弹一个对话框，用户点「允许」才算数。
     * 第二条在 vivo 上落到的是 iQOO 自己的后台管理页，管的范围比 Doze 宽，
     * 但它不是原生那个权限界面 —— 用户要的是前者。
     *
     * 用标准 action 而不是硬编码 `com.iqoo.powersaving` 那个类名：类名是 ROM
     * 私有实现，换个版本就可能不存在，标准 action 由系统自己决定落到哪。
     *
     * @return true = 系统接下了这个 intent。**不等于用户真的改了什么** ——
     *   那是用户在那个页面里做的事，我们无从知道。
     */
    fun openSystemSettings(context: android.content.Context): Boolean {
        val pkg = android.net.Uri.parse("package:$PKG")
        val intents = listOf(
            // 1. 安卓原生授权弹窗 —— 用户点了「允许」才算数，这才是正统入口。
            android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                pkg,
            ),
            // 2. 电池优化列表页（vivo 上落在 iQOO 后台管理，管的范围更宽）。
            android.content.Intent(
                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            ),
            // 3. 应用详情页：任何 Android 机都有，里面必有电池相关入口。
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                pkg,
            ),
        )
        // 第一个能解析的就用它；都不行才告诉调用方"打不开"。
        return intents.firstOrNull { it.resolveActivity(context.packageManager) != null }
            ?.let {
                runCatching {
                    context.startActivity(
                        // 从 Activity 之外启动时要新任务栈，否则在某些 ROM 上不显示。
                        it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
            } ?: false
    }

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
