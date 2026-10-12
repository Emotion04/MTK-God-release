package magicau.mtkcontroller.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import magicau.mtkcontroller.data.settingsStore
import magicau.mtkcontroller.domain.model.DEFAULT_HOME_CARDS
import magicau.mtkcontroller.domain.model.HomeCard
import magicau.mtkcontroller.domain.model.HomeCardType
import java.util.UUID

/**
 * User preferences that are not part of a profile: which tab to open on launch,
 * and the home dashboard layout.
 *
 * Routes are stored as plain strings so this layer stays free of UI types.
 */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val defaultTabKey = stringPreferencesKey("default_tab_route")
    private val homeCardsKey = stringPreferencesKey("home_cards_json")
    private val migratedV2Key = booleanPreferencesKey("migrated_v2_quick_settings")
    private val paletteKey = stringPreferencesKey("theme_palette")
    // 自定义强调色存"色相 + 浓度"，不存 ARGB —— 真正的颜色由 MorandiAccent 推导，
    // 只存推导的输入才能保证"白字可读"这条不变量始终成立（直接存 ARGB 的话，
    // 一个越界值就能绕过它）。
    private val customHueKey = intPreferencesKey("theme_custom_hue")
    private val customConcentrationKey = intPreferencesKey("theme_custom_concentration")
    private val navBarStyleKey = stringPreferencesKey("nav_bar_style")
    private val navBarCustomKey = stringPreferencesKey("nav_bar_custom_palette")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val shapeStyleKey = stringPreferencesKey("shape_style")
    private val motionSpeedKey = stringPreferencesKey("motion_speed")
    private val tabSwipeModeKey = stringPreferencesKey("tab_swipe_mode")
    private val cpuLayoutModeKey = stringPreferencesKey("cpu_layout_mode")
    private val rangeControlModeKey = stringPreferencesKey("range_control_mode")
    private val navBarVisibilityKey = stringPreferencesKey("nav_bar_visibility")
    private val tabRestoreModeKey = stringPreferencesKey("tab_restore_mode")
    private val logLevelKey = stringPreferencesKey("log_level")
    private val applyModeKey = stringPreferencesKey("apply_mode")
    private val reapplyIntervalKey = longPreferencesKey("reapply_interval_ms")

    val defaultTabRoute: Flow<String?> = context.settingsStore.data.map { it[defaultTabKey] }

    val themePalette: Flow<String> = context.settingsStore.data.map { it[paletteKey] ?: "MORANDI_SAGE" }

    /** 自定义强调色的色相，0–360。 */
    val customHue: Flow<Int> =
        context.settingsStore.data.map { it[customHueKey] ?: DEFAULT_CUSTOM_HUE }

    /** 自定义强调色的浓度，0–100。 */
    val customConcentration: Flow<Int> =
        context.settingsStore.data.map { it[customConcentrationKey] ?: DEFAULT_CUSTOM_CONCENTRATION }

    suspend fun setCustomAccent(hueDeg: Int, concentration: Int) {
        context.settingsStore.edit {
            it[customHueKey] = hueDeg.coerceIn(0, 360)
            it[customConcentrationKey] = concentration.coerceIn(0, 100)
        }
    }

    suspend fun setThemePalette(name: String) {
        context.settingsStore.edit { it[paletteKey] = name }
    }

    val navBarStyle: Flow<String> = context.settingsStore.data.map { it[navBarStyleKey] ?: "COLORFUL" }

    suspend fun setNavBarStyle(name: String) {
        context.settingsStore.edit { it[navBarStyleKey] = name }
    }

    val navBarCustomPalette: Flow<String> =
        context.settingsStore.data.map { it[navBarCustomKey] ?: "TEAL" }

    suspend fun setNavBarCustomPalette(name: String) {
        context.settingsStore.edit { it[navBarCustomKey] = name }
    }

    val themeMode: Flow<String> = context.settingsStore.data.map { it[themeModeKey] ?: "SYSTEM" }

    /** 圆角规格。三档预设，见 [magicau.mtkcontroller.ui.theme.ShapeStyle]。 */
    val shapeStyle: Flow<String> = context.settingsStore.data.map { it[shapeStyleKey] ?: "STANDARD" }


    /** 动画速度：轻快 / 标准 / 舒缓。 */
    val motionSpeed: Flow<String> =
        context.settingsStore.data.map { it[motionSpeedKey] ?: "SYSTEM" }

    /** 拖底栏松手后的行为：松手即切换 / 停留后切换。 */
    val tabSwipeMode: Flow<String> =
        context.settingsStore.data.map { it[tabSwipeModeKey] ?: "NEAREST" }

    suspend fun setThemeMode(name: String) {
        context.settingsStore.edit { it[themeModeKey] = name }
    }

    suspend fun setShapeStyle(name: String) {
        context.settingsStore.edit { it[shapeStyleKey] = name }
    }


    suspend fun setMotionSpeed(name: String) {
        context.settingsStore.edit { it[motionSpeedKey] = name }
    }

    suspend fun setTabSwipeMode(name: String) {
        context.settingsStore.edit { it[tabSwipeModeKey] = name }
    }

    /** CPU 页控制区布局：分卡片 / 合并卡片。 */
    val cpuLayoutMode: Flow<String> =
        context.settingsStore.data.map { it[cpuLayoutModeKey] ?: "UNIFIED" }

    suspend fun setCpuLayoutMode(name: String) {
        context.settingsStore.edit { it[cpuLayoutModeKey] = name }
    }

    /** 频率区间的调节方式：分段条 / 双向滑块。 */
    val rangeControlMode: Flow<String> =
        context.settingsStore.data.map { it[rangeControlModeKey] ?: "SEGMENT" }

    suspend fun setRangeControlMode(name: String) {
        context.settingsStore.edit { it[rangeControlModeKey] = name }
    }

    /** 悬浮底栏的滚动行为：固定 / 上滑隐藏。 */
    val navBarVisibility: Flow<String> =
        context.settingsStore.data.map { it[navBarVisibilityKey] ?: "AUTO_HIDE" }

    suspend fun setNavBarVisibility(name: String) {
        context.settingsStore.edit { it[navBarVisibilityKey] = name }
    }

    /** 切回标签时：保留原二级页面 / 回到标签主页。 */
    val tabRestoreMode: Flow<String> =
        context.settingsStore.data.map { it[tabRestoreModeKey] ?: "KEEP" }

    suspend fun setTabRestoreMode(name: String) {
        context.settingsStore.edit { it[tabRestoreModeKey] = name }
    }

    val logLevel: Flow<String> = context.settingsStore.data.map { it[logLevelKey] ?: "INFO" }

    suspend fun setLogLevel(name: String) {
        context.settingsStore.edit { it[logLevelKey] = name }
    }

    val applyMode: Flow<String> = context.settingsStore.data.map { it[applyModeKey] ?: "SINGLE" }

    suspend fun setApplyMode(name: String) {
        context.settingsStore.edit { it[applyModeKey] = name }
    }

    /** Bounded so a typo cannot turn into a binder hammering loop. */
    val reapplyIntervalMs: Flow<Long> = context.settingsStore.data.map {
        (it[reapplyIntervalKey] ?: DEFAULT_REAPPLY_INTERVAL_MS)
            .coerceIn(MIN_REAPPLY_INTERVAL_MS, MAX_REAPPLY_INTERVAL_MS)
    }

    suspend fun setReapplyIntervalMs(value: Long) {
        context.settingsStore.edit {
            it[reapplyIntervalKey] = value.coerceIn(MIN_REAPPLY_INTERVAL_MS, MAX_REAPPLY_INTERVAL_MS)
        }
    }

    companion object {
        const val DEFAULT_REAPPLY_INTERVAL_MS = 2_000L

        /** 自定义强调色的初始位置：与默认调色板（莫兰迪鼠尾草）同色。 */
        const val DEFAULT_CUSTOM_HUE = 107
        const val DEFAULT_CUSTOM_CONCENTRATION = 25

        /**
         * Each tick costs a release + acquire round trip. Below ~250 ms the
         * binder traffic stops being free and the request never settles, so
         * this is a hard floor rather than a suggestion.
         */
        const val MIN_REAPPLY_INTERVAL_MS = 250L
        const val MAX_REAPPLY_INTERVAL_MS = 600_000L
    }

    val homeCards: Flow<List<HomeCard>> = context.settingsStore.data.map { prefs ->
        prefs[homeCardsKey]
            ?.let { raw -> runCatching { json.decodeFromString<List<HomeCard>>(raw) }.getOrNull() }
            ?: DEFAULT_HOME_CARDS
    }

    /**
     * One-time upgrade for installs that predate the quick-settings card: make
     * sure it shows up without forcing the user to reset their layout.
     */
    suspend fun migrateIfNeeded() {
        // 仅升级时补入一次；用户之后移除不会在下次启动重新出现。
        val testsKey = booleanPreferencesKey("migrated_test_center")
        context.settingsStore.edit { prefs ->
            if (prefs[testsKey] != true) {
                val current = prefs[homeCardsKey]?.let { raw ->
                    runCatching { json.decodeFromString<List<HomeCard>>(raw) }.getOrNull()
                } ?: DEFAULT_HOME_CARDS
                val updated = if (current.any { it.type == HomeCardType.TEST_CENTER }) current
                    else current + HomeCard("card-test-center", HomeCardType.TEST_CENTER)
                prefs[homeCardsKey] = json.encodeToString(updated)
                prefs[testsKey] = true
            }
        }
        val prefs = context.settingsStore.data.first()
        if (prefs[migratedV2Key] == true) return
        val current = homeCards.first()
        if (current.none { it.type == HomeCardType.QUICK_SETTINGS }) {
            write(
                listOf(HomeCard(id = "card-quick-settings", type = HomeCardType.QUICK_SETTINGS)) + current,
            )
        }
        context.settingsStore.edit { it[migratedV2Key] = true }
    }

    suspend fun setDefaultTabRoute(route: String?) {
        context.settingsStore.edit {
            if (route == null) it.remove(defaultTabKey) else it[defaultTabKey] = route
        }
    }

    suspend fun addCard(type: HomeCardType) {
        val current = homeCards.first()
        if (current.any { it.type == type }) return
        write(current + HomeCard(id = UUID.randomUUID().toString(), type = type))
    }

    suspend fun removeCard(id: String) {
        write(homeCards.first().filterNot { it.id == id })
    }

    suspend fun moveCard(fromIndex: Int, toIndex: Int) {
        val current = homeCards.first().toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        current.add(toIndex, current.removeAt(fromIndex))
        write(current)
    }

    suspend fun resetCards() = write(DEFAULT_HOME_CARDS)

    /** Wholesale replace, used by backup import. */
    suspend fun replaceCards(cards: List<HomeCard>) = write(cards)

    private suspend fun write(cards: List<HomeCard>) {
        context.settingsStore.edit { it[homeCardsKey] = json.encodeToString(cards) }
    }
}
