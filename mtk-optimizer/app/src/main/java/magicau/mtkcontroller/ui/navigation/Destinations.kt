package magicau.mtkcontroller.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import magicau.mtkcontroller.R

/**
 * Bottom-navigation destinations.
 *
 * Icons come from material-icons-core (the extended set adds tens of megabytes
 * to the APK for a handful of glyphs). Each destination carries its own accent
 * colour so the bar reads as colourful without needing custom art.
 */
enum class Destination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val accent: Color,
) {
    HOME("home", R.string.nav_home, Icons.Filled.Home, Color(0xFF2E7D32)),
    CPU("cpu", R.string.nav_cpu, Icons.Filled.Build, Color(0xFFB4531A)),
    TOOLS("tools", R.string.nav_tools, Icons.Filled.Star, Color(0xFF6A4C93)),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings, Color(0xFF4A6572)),
    ;

    companion object {
        fun fromRoute(route: String?): Destination =
            if (route == "gpu") CPU else entries.firstOrNull { it.route == route } ?: HOME
    }
}

/** Sub-screens reached from Settings. */
object SubRoutes {
    /** CPU 页的「高级设置」二级页。 */
    const val CPU_ADVANCED = "cpu/advanced"
    /**
     * 设置分类的二级页。**一条路由装全部分类**，分类名走路径参数。
     *
     * 不给每个分类各开一条常量：那样每加一个分类就要动路由表、导航图和这里，
     * 而它们的行为完全一样（都是"渲染某个分类的内容"）。
     */
    const val SETTINGS_CATEGORY = "settings/cat"
    const val SETTINGS_CATEGORY_ARG = "category"
    fun settingsCategory(category: magicau.mtkcontroller.feature.settings.SettingsCategory) =
        "$SETTINGS_CATEGORY/${category.name}"
    const val SMART = "tools/smart"
    const val SMART_BACKUP = "tools/smart/backup"
    const val CONFIG = "tools/config"
    const val MAINTENANCE = "tools/maintenance"
    const val UNAVAILABLE = "tools/unavailable"
    const val TESTS = "settings/tests"
    const val FEATURE_TRIAL = "settings/tests/trial"
    const val DIAGNOSTICS = "settings/diagnostics"
    const val NOTIFICATIONS = "settings/notifications"
    const val TWEAKS = "tools/display"
    const val GAME = "tools/game"
    const val STATUS = "tools/status"
    const val LOGS = "settings/logs"
    const val EXPORT = "settings/export"
    const val ARCHIVE = "settings/archive"
    const val LAB = "settings/lab"
    const val LAB_PANEL = "settings/lab/panel"
    const val LAB_RAW = "settings/lab/raw"
    const val APP_THERMAL = "thermal/app"
}
