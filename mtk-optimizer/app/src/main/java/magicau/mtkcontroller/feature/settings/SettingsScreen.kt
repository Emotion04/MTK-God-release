package magicau.mtkcontroller.feature.settings

import magicau.mtkcontroller.ui.components.SUB_SCREEN_EXTRA_PADDING
import magicau.mtkcontroller.ui.components.LocalNavBarClearance
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.log.LogLevel
import magicau.mtkcontroller.data.tweaks.BrightnessBackend
import magicau.mtkcontroller.data.tweaks.TouchBackend
import magicau.mtkcontroller.data.tweaks.TweakCommands
import magicau.mtkcontroller.domain.model.ApplyMode
import magicau.mtkcontroller.domain.model.BackupCategory
import magicau.mtkcontroller.domain.model.CpuLayoutMode
import magicau.mtkcontroller.domain.model.NavBarStyle
import magicau.mtkcontroller.domain.model.NavBarVisibility
import magicau.mtkcontroller.domain.model.RangeControlMode
import magicau.mtkcontroller.domain.model.ThemeMode
import magicau.mtkcontroller.ui.components.FailureNote
import magicau.mtkcontroller.ui.components.InsetDivider
import magicau.mtkcontroller.ui.components.SettingsCard
import magicau.mtkcontroller.ui.components.SettingsRow
import magicau.mtkcontroller.ui.components.ShizukuStatusCard
import magicau.mtkcontroller.domain.model.TabRestoreMode
import magicau.mtkcontroller.ui.theme.MorandiAccent
import magicau.mtkcontroller.ui.descriptionRes
import magicau.mtkcontroller.ui.labelRes
import magicau.mtkcontroller.ui.navigation.Destination
import magicau.mtkcontroller.ui.theme.AppPalette

private val PaletteIcon = Color(0xFF6A4C93)
private val ThemeIcon = Color(0xFF55606E)
private val TabIcon = Color(0xFF2E7D32)
private val ToolsIcon = Color(0xFFB4531A)
private val NotifyIcon = Color(0xFF1565C0)
private val DiagIcon = Color(0xFF0B6E6E)
private val NavIcon = Color(0xFF7A8FA6)
private val LogIcon = Color(0xFF6B6B68)
private val ReapplyIcon = Color(0xFF9C4146)
private val BackupIcon = Color(0xFF4A6572)
private val LabIcon = Color(0xFF6A4C93)

// ===========================================================================
// 一级：只列分类
// ===========================================================================

/**
 * 设置首页。**只有授权状态和分类入口，没有具体设置项。**
 *
 * ## 为什么必须分层
 *
 * 设置项会一直增加。平铺的话一级页面长度 = 设置项数量，加一项长一点，加十项翻一屏；
 * 分层之后一级页面长度 = **分类数量**，跟里面装了多少项无关。
 *
 * ## 哪条留在一级
 *
 * 只有"授权状态" —— 它是这一页唯一**需要随时确认、而且会自己变**的信息
 * （Shizuku 可能掉线），藏进二级就看不见了。其余全是分类入口。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onOpenCategory: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(top = 12.dp, bottom = LocalNavBarClearance.current),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            // 页面标题和底栏标签是同一个词、同一件事，共用一份，避免改了一处忘了另一处。
            stringResource(Destination.SETTINGS.labelRes),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
        )

        ShizukuStatusCard(
            privilege = state.privilege,
            onClick = actions.onRequestAuth,
        )

        SettingsCard {
            SettingsCategory.entries.forEachIndexed { index, category ->
                if (index > 0) InsetDivider()
                SettingsRow(
                    icon = category.icon(),
                    iconTint = category.tint(),
                    title = stringResource(category.labelRes),
                    subtitle = stringResource(category.subtitleRes),
                    onClick = { onOpenCategory(category) },
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        // 关于直接摊在底部，不做分类：为它单开一页只装静态信息，多一跳不值得。
        AboutCard()
    }
}

private fun SettingsCategory.icon(): ImageVector = when (this) {
    SettingsCategory.GENERAL -> Icons.Filled.Home
    // 外观与行为：主题色、圆角、动画都归它，星形比"喜欢"更贴切。
    SettingsCategory.APPEARANCE -> Icons.Filled.Star
    // 实验室：core 图标集里没有烧瓶，用 res/drawable/ic_lab_flask.xml。
    SettingsCategory.LAB -> Icons.Filled.Info
    SettingsCategory.NOTIFICATIONS -> Icons.Filled.Notifications
    // 维护与诊断：齿轮比"列表"更像"维护"。
    SettingsCategory.MAINTENANCE -> Icons.Filled.Settings
    // 备份与恢复：恢复语义，刷新箭头是对的。
    SettingsCategory.BACKUP -> Icons.Filled.Refresh
}

private fun SettingsCategory.tint(): Color = when (this) {
    SettingsCategory.GENERAL -> TabIcon
    SettingsCategory.APPEARANCE -> ThemeIcon
    SettingsCategory.LAB -> LabIcon
    SettingsCategory.NOTIFICATIONS -> NotifyIcon
    SettingsCategory.MAINTENANCE -> LogIcon
    SettingsCategory.BACKUP -> BackupIcon
}

// ===========================================================================
// 二级：分类的具体内容
// ===========================================================================

@Composable
fun SettingsCategoryScreen(
    category: SettingsCategory,
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
            .padding(top = 8.dp, bottom = SUB_SCREEN_EXTRA_PADDING),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (category) {
            SettingsCategory.GENERAL -> GeneralSection(state, actions)
            SettingsCategory.APPEARANCE -> AppearanceSection(state, actions)
            SettingsCategory.LAB -> LabSection(actions)
            SettingsCategory.NOTIFICATIONS -> NotificationSection(actions)
            SettingsCategory.MAINTENANCE -> MaintenanceSection(state, actions)
            SettingsCategory.BACKUP -> BackupSection(state, actions)
        }
    }
}

// --- 通用 -----------------------------------------------------------------

@Composable
private fun GeneralSection(state: SettingsUiState, actions: SettingsActions) {
    var tabExpanded by remember { mutableStateOf(false) }
    var applyModeExpanded by remember { mutableStateOf(false) }
    var backendDialog by remember { mutableStateOf<String?>(null) }
    var ceilingDialog by remember { mutableStateOf(false) }

    if (backendDialog != null) {
        AlertDialog(
            onDismissRequest = { backendDialog = null },
            title = {
                Text(
                    stringResource(
                        if (backendDialog == "touch") R.string.settings_touch_backend
                        else R.string.settings_brightness_backend,
                    ),
                )
            },
            text = {
                Column {
                    if (backendDialog == "touch") {
                        TouchBackend.entries.forEach { backend ->
                            OptionRow(stringResource(backend.labelRes), selected = state.touchBackend == backend, onClick = {
                                if (!state.busy) { actions.onSetTouchBackend(backend); backendDialog = null }
                            })
                        }
                    } else {
                        BrightnessBackend.entries.forEach { backend ->
                            OptionRow(stringResource(backend.labelRes), selected = state.brightnessBackend == backend, onClick = {
                                if (!state.busy) { actions.onSetBrightnessBackend(backend); backendDialog = null }
                            })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { backendDialog = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (ceilingDialog) {
        AlertDialog(
            onDismissRequest = { ceilingDialog = false },
            title = { Text(stringResource(R.string.freq_ceiling_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.freq_ceiling_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    TweakCommands.RangeMode.entries.forEach { mode ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = state.freqRangeMode == mode,
                                onClick = { ceilingDialog = false; actions.onSetFreqRangeMode(mode) },
                            )
                            Text(stringResource(mode.labelRes))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ceilingDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    SettingsCard {
        SettingsRow(
            icon = Icons.Filled.Home,
            iconTint = TabIcon,
            title = stringResource(R.string.settings_default_tab),
            subtitle = stringResource(R.string.settings_default_tab_sub),
            trailingText = stringResource(state.defaultTab.labelRes),
            onClick = { tabExpanded = !tabExpanded },
        )
        AnimatedVisibility(visible = tabExpanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                Destination.entries.forEach { destination ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.defaultTab == destination,
                                role = Role.RadioButton,
                                onClick = { actions.onSetDefaultTab(destination) },
                            )
                            .padding(start = 56.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RadioButton(selected = state.defaultTab == destination, onClick = null)
                        Icon(destination.icon, contentDescription = null, modifier = Modifier.size(17.dp))
                        Text(stringResource(destination.labelRes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.Refresh,
            iconTint = ReapplyIcon,
            title = stringResource(R.string.settings_apply_mode),
            subtitle = stringResource(state.applyMode.descriptionRes),
            trailingText = stringResource(state.applyMode.labelRes),
            onClick = { applyModeExpanded = !applyModeExpanded },
        )
        AnimatedVisibility(visible = applyModeExpanded) {
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                ApplyMode.entries.forEach { mode ->
                    OptionRow(
                        label = stringResource(mode.labelRes),
                        detail = stringResource(mode.descriptionRes),
                        selected = state.applyMode == mode,
                        onClick = { actions.onSetApplyMode(mode) },
                    )
                }
                if (state.applyMode == ApplyMode.POLLING) {
                    IntervalRow(intervalMs = state.reapplyIntervalMs, onCommit = actions.onSetReapplyInterval)
                    Text(
                        stringResource(R.string.settings_reapply_interval_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 2.dp),
                    )
                }
            }
        }

        InsetDivider()
        SettingsRow(
            icon = Icons.Default.Build,
            iconTint = ToolsIcon,
            title = stringResource(R.string.settings_touch_backend),
            trailingText = stringResource(state.touchBackend.labelRes),
            onClick = { backendDialog = "touch" },
        )
        InsetDivider()
        SettingsRow(
            icon = Icons.Default.Star,
            iconTint = PaletteIcon,
            title = stringResource(R.string.settings_brightness_backend),
            trailingText = stringResource(state.brightnessBackend.labelRes),
            onClick = { backendDialog = "brightness" },
        )
        state.touchBackendMessage?.let {
            FailureNote(
                failure = it,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
        }

        InsetDivider()
        // 与"应用方式"并列：一个说什么时候下发，一个说下发什么。
        SettingsRow(
            icon = Icons.Default.Build,
            iconTint = ToolsIcon,
            title = stringResource(R.string.freq_ceiling_title),
            subtitle = stringResource(state.freqRangeMode.labelRes),
            onClick = { ceilingDialog = true },
        )
    }
}

// --- 外观与行为 ------------------------------------------------------------

@Composable
private fun AppearanceSection(state: SettingsUiState, actions: SettingsActions) {
    var themeModeExpanded by remember { mutableStateOf(false) }
    var speedExpanded by remember { mutableStateOf(false) }
    var shapeExpanded by remember { mutableStateOf(false) }
    var paletteExpanded by remember { mutableStateOf(false) }
    var navStyleExpanded by remember { mutableStateOf(false) }
    var swipeExpanded by remember { mutableStateOf(false) }
    var cpuLayoutExpanded by remember { mutableStateOf(false) }
    var rangeModeExpanded by remember { mutableStateOf(false) }
    var tabRestoreExpanded by remember { mutableStateOf(false) }
    var navVisibilityExpanded by remember { mutableStateOf(false) }

    SettingsCard {
        SettingsRow(
            icon = Icons.Filled.Star,
            iconTint = ThemeIcon,
            title = stringResource(R.string.settings_theme_mode),
            subtitle = stringResource(state.themeMode.descriptionRes),
            trailingText = stringResource(state.themeMode.labelRes),
            onClick = { themeModeExpanded = !themeModeExpanded },
        )
        AnimatedVisibility(visible = themeModeExpanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    OptionRow(
                        label = stringResource(mode.labelRes),
                        detail = stringResource(mode.descriptionRes),
                        selected = state.themeMode == mode,
                        onClick = { actions.onSetThemeMode(mode) },
                    )
                }
            }
        }

        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.Refresh,
            iconTint = ThemeIcon,
            title = stringResource(R.string.settings_motion_speed),
            subtitle = stringResource(state.motionSpeed.descriptionRes),
            trailingText = stringResource(state.motionSpeed.labelRes),
            onClick = { speedExpanded = !speedExpanded },
        )
        AnimatedVisibility(visible = speedExpanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                magicau.mtkcontroller.ui.theme.MotionSpeed.entries.forEach { speed ->
                    OptionRow(
                        label = stringResource(speed.labelRes),
                        detail = stringResource(speed.descriptionRes),
                        selected = state.motionSpeed == speed,
                        onClick = { actions.onSetMotionSpeed(speed) },
                    )
                }
            }
        }

        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.Star,
            iconTint = ThemeIcon,
            title = stringResource(R.string.settings_shape),
            subtitle = stringResource(R.string.settings_shape_sub),
            trailingText = stringResource(state.shapeStyle.labelRes),
            onClick = { shapeExpanded = !shapeExpanded },
        )
        AnimatedVisibility(visible = shapeExpanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                magicau.mtkcontroller.ui.theme.ShapeStyle.entries.forEach { style ->
                    OptionRow(
                        label = stringResource(style.labelRes),
                        selected = state.shapeStyle == style,
                        onClick = { actions.onSetShapeStyle(style) },
                    )
                }
            }
        }

        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.Favorite,
            iconTint = PaletteIcon,
            title = stringResource(R.string.settings_palette),
            subtitle = stringResource(state.palette.labelRes),
            onClick = { paletteExpanded = !paletteExpanded },
        )
        AnimatedVisibility(visible = paletteExpanded) {
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                AppPalette.entries.groupBy { it.groupRes }.forEach { (group, palettes) ->
                    Text(
                        stringResource(group),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 56.dp, top = 6.dp, bottom = 2.dp),
                    )
                    if (group == R.string.palette_group_morandi) {
                        // 莫兰迪这组有十几种，逐个铺成单选行会占掉大半屏，而且隔着
                        // 一行文字没法比色。改成色板网格：一眼扫完，点一下就换。
                        // 选中态用描边 + 对勾，不靠颜色深浅 —— 那在浅色块上看不出。
                        MorandiSwatchGrid(
                            palettes = palettes,
                            selected = state.palette,
                            onPick = { actions.onSetPalette(it) },
                        )
                        // 自定义：色相全开、浓度受限，亮度不给（给了就破坏白字可读）。
                        // 两个滑杆的值来自 ViewModel，改动即存即生效。
                        CustomAccentRow(
                            selected = state.palette,
                            hueDeg = state.customHue,
                            concentration = state.customConcentration,
                            onHueChange = actions.onSetCustomHue,
                            onConcentrationChange = actions.onSetCustomConcentration,
                            onSelectCustom = { actions.onSetPalette(AppPalette.CUSTOM) },
                        )
                    } else {
                        palettes.forEach { palette ->
                            OptionRow(
                                label = stringResource(palette.labelRes),
                                selected = state.palette == palette,
                                swatch = palette.accent,
                                onClick = { actions.onSetPalette(palette) },
                            )
                        }
                    }
                }
            }
        }

        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.List,
            iconTint = NavIcon,
            title = stringResource(R.string.settings_nav_visibility),
            subtitle = stringResource(state.navBarVisibility.descriptionRes),
            trailingText = stringResource(state.navBarVisibility.labelRes),
            onClick = { navVisibilityExpanded = !navVisibilityExpanded },
        )
        AnimatedVisibility(visible = navVisibilityExpanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                NavBarVisibility.entries.forEach { mode ->
                    OptionRow(
                        label = stringResource(mode.labelRes),
                        detail = stringResource(mode.descriptionRes),
                        selected = state.navBarVisibility == mode,
                        onClick = { actions.onSetNavBarVisibility(mode) },
                    )
                }
            }
        }

        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.List,
            iconTint = NavIcon,
            title = stringResource(R.string.settings_nav_style),
            subtitle = stringResource(state.navBarStyle.descriptionRes),
            trailingText = stringResource(state.navBarStyle.labelRes),
            onClick = { navStyleExpanded = !navStyleExpanded },
        )
        AnimatedVisibility(visible = navStyleExpanded) {
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                NavBarStyle.entries.forEach { style ->
                    OptionRow(
                        label = stringResource(style.labelRes),
                        detail = stringResource(style.descriptionRes),
                        selected = state.navBarStyle == style,
                        onClick = { actions.onSetNavBarStyle(style) },
                    )
                }
                if (state.navBarStyle == NavBarStyle.CUSTOM) {
                    Text(
                        stringResource(R.string.settings_nav_custom_palette),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 62.dp, top = 6.dp, bottom = 2.dp),
                    )
                    AppPalette.entries.forEach { palette ->
                        OptionRow(
                            label = stringResource(palette.labelRes),
                            detail = stringResource(palette.groupRes),
                            selected = state.navBarCustomPalette == palette,
                            swatch = palette.accent,
                            onClick = { actions.onSetNavBarCustomPalette(palette) },
                        )
                    }
                }
            }
        }

        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.List,
            iconTint = ThemeIcon,
            title = stringResource(R.string.tab_swipe_title),
            subtitle = stringResource(state.tabSwipeMode.descriptionRes),
            trailingText = stringResource(state.tabSwipeMode.labelRes),
            onClick = { swipeExpanded = !swipeExpanded },
        )
        AnimatedVisibility(visible = swipeExpanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                magicau.mtkcontroller.ui.navigation.TabSwipeMode.entries.forEach { mode ->
                    OptionRow(
                        label = stringResource(mode.labelRes),
                        detail = stringResource(mode.descriptionRes),
                        selected = state.tabSwipeMode == mode,
                        onClick = { actions.onSetTabSwipeMode(mode) },
                    )
                }
            }
        }

        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.List,
            iconTint = ThemeIcon,
            title = stringResource(R.string.settings_cpu_layout),
            subtitle = stringResource(state.cpuLayoutMode.descriptionRes),
            trailingText = stringResource(state.cpuLayoutMode.labelRes),
            onClick = { cpuLayoutExpanded = !cpuLayoutExpanded },
        )
        AnimatedVisibility(visible = cpuLayoutExpanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                CpuLayoutMode.entries.forEach { mode ->
                    OptionRow(
                        label = stringResource(mode.labelRes),
                        detail = stringResource(mode.descriptionRes),
                        selected = state.cpuLayoutMode == mode,
                        onClick = { actions.onSetCpuLayoutMode(mode) },
                    )
                }
            }
        }

        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.List,
            iconTint = ThemeIcon,
            title = stringResource(R.string.settings_range_control),
            subtitle = stringResource(state.rangeControlMode.descriptionRes),
            trailingText = stringResource(state.rangeControlMode.labelRes),
            onClick = { rangeModeExpanded = !rangeModeExpanded },
        )
        AnimatedVisibility(visible = rangeModeExpanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                RangeControlMode.entries.forEach { mode ->
                    OptionRow(
                        label = stringResource(mode.labelRes),
                        detail = stringResource(mode.descriptionRes),
                        selected = state.rangeControlMode == mode,
                        onClick = { actions.onSetRangeControlMode(mode) },
                    )
                }
            }
        }

        InsetDivider()
        // 切回标签时：保留二级页面还是回到主页。放在"频率区间调节方式"后面，
        // 两者都是"这一页怎么用"的选择，不是设备能力。
        SettingsRow(
            icon = Icons.Filled.Refresh,
            iconTint = ThemeIcon,
            title = stringResource(R.string.settings_tab_restore),
            subtitle = stringResource(state.tabRestoreMode.descriptionRes),
            trailingText = stringResource(state.tabRestoreMode.labelRes),
            onClick = { tabRestoreExpanded = !tabRestoreExpanded },
        )
        AnimatedVisibility(visible = tabRestoreExpanded) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                TabRestoreMode.entries.forEach { mode ->
                    OptionRow(
                        label = stringResource(mode.labelRes),
                        detail = stringResource(mode.descriptionRes),
                        selected = state.tabRestoreMode == mode,
                        onClick = { actions.onSetTabRestoreMode(mode) },
                    )
                }
            }
        }

        InsetDivider()
        // 触控、插帧、旋转建议、高温降亮度都在这条后面 —— 它们都是"改变观感与手感"的开关。
        SettingsRow(
            icon = Icons.Filled.Build,
            iconTint = ToolsIcon,
            title = stringResource(R.string.settings_tools),
            subtitle = stringResource(R.string.settings_tools_sub),
            onClick = actions.onOpenDisplayTweaks,
        )
    }
}

// --- 实验室 ----------------------------------------------------------------

@Composable
private fun LabSection(actions: SettingsActions) {
    SettingsCard {
        SettingsRow(
            iconPainter = painterResource(R.drawable.ic_lab_flask),
            iconTint = LabIcon,
            title = stringResource(R.string.settings_lab),
            subtitle = stringResource(R.string.settings_lab_sub),
            onClick = actions.onOpenLab,
        )
        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.List,
            iconTint = LabIcon,
            title = stringResource(R.string.app_thermal_title),
            subtitle = stringResource(R.string.settings_app_thermal_sub),
            onClick = actions.onOpenAppThermal,
        )
        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.CheckCircle,
            iconTint = LabIcon,
            title = stringResource(R.string.settings_tests),
            subtitle = stringResource(R.string.settings_tests_sub),
            onClick = actions.onOpenTests,
        )
        InsetDivider()
        // 归档挨着实验室：两者都是"不常来但需要时能找到"的地方，归档里的更旧一层。
        SettingsRow(
            icon = Icons.Filled.List,
            iconTint = LabIcon,
            title = stringResource(R.string.archive_title),
            subtitle = stringResource(R.string.archive_sub),
            onClick = actions.onOpenArchive,
        )
    }
}

// --- 通知 ------------------------------------------------------------------

@Composable
private fun NotificationSection(actions: SettingsActions) {
    SettingsCard {
        SettingsRow(
            icon = Icons.Filled.Notifications,
            iconTint = NotifyIcon,
            title = stringResource(R.string.settings_notifications),
            subtitle = stringResource(R.string.settings_notifications_sub),
            onClick = actions.onOpenNotifications,
        )
    }
}

// --- 维护与诊断 ------------------------------------------------------------

@Composable
private fun MaintenanceSection(state: SettingsUiState, actions: SettingsActions) {
    var logLevelExpanded by remember { mutableStateOf(false) }

    SettingsCard {
        SettingsRow(
            icon = Icons.Filled.Search,
            iconTint = DiagIcon,
            title = stringResource(R.string.settings_diagnostics),
            subtitle = stringResource(R.string.settings_diagnostics_sub),
            onClick = actions.onOpenDiagnostics,
        )
        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.List,
            iconTint = LogIcon,
            title = stringResource(R.string.settings_log_level),
            subtitle = stringResource(state.logLevel.descriptionRes),
            trailingText = stringResource(state.logLevel.labelRes),
            onClick = { logLevelExpanded = !logLevelExpanded },
        )
        AnimatedVisibility(visible = logLevelExpanded) {
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                LogLevel.entries.forEach { level ->
                    OptionRow(
                        label = stringResource(level.labelRes),
                        detail = stringResource(level.descriptionRes),
                        selected = state.logLevel == level,
                        onClick = { actions.onSetLogLevel(level) },
                    )
                }
            }
        }
        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.List,
            iconTint = LogIcon,
            title = stringResource(R.string.settings_logs),
            subtitle = stringResource(R.string.settings_logs_sub),
            onClick = actions.onOpenLogs,
        )
        InsetDivider()
        // 与"运行日志"分开：那是给用户自己看的，这里是打包发给开发者的 ——
        // 目的不同、动作也不同（一个看，一个导出）。
        SettingsRow(
            icon = Icons.Filled.Share,
            iconTint = LogIcon,
            title = stringResource(R.string.settings_export),
            subtitle = stringResource(R.string.settings_export_sub),
            onClick = actions.onOpenExport,
        )
    }
}

// --- 备份与恢复 ------------------------------------------------------------

/**
 * 备份与恢复。
 *
 * ## 范围选择收进对话框
 *
 * 「导出哪几类」原来作为一排 chip **常驻**在页面上，于是这一页有三样东西同时摊开：
 * 导出范围、导出按钮、导入范围、导入按钮，读起来不知道从哪下手。
 * 范围是**动作的参数**，不是页面上的常驻状态 —— 所以它跟着动作走：
 * 点"导出"才弹出来问一次。
 */
@Composable
private fun BackupSection(state: SettingsUiState, actions: SettingsActions) {
    var exportDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(actions.onExportBackup) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(actions.onPrepareImport) }

    if (exportDialog) {
        BackupScopeDialog(
            title = stringResource(R.string.backup_export_scope),
            selected = state.exportCategories,
            onToggle = actions.onToggleExportCategory,
            onConfirm = {
                exportDialog = false
                exportLauncher.launch(state.backupFileName)
            },
            onDismiss = { exportDialog = false },
        )
    }

    SettingsCard {
        SettingsRow(
            icon = Icons.Filled.Refresh,
            iconTint = BackupIcon,
            title = stringResource(R.string.settings_smart_backup),
            subtitle = stringResource(R.string.settings_smart_backup_sub),
            onClick = actions.onOpenSmartBackup,
        )
        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.Refresh,
            iconTint = BackupIcon,
            title = stringResource(R.string.settings_export_config),
            subtitle = stringResource(R.string.settings_export_config_sub),
            onClick = { exportDialog = true },
        )
        InsetDivider()
        SettingsRow(
            icon = Icons.Filled.Info,
            iconTint = BackupIcon,
            title = stringResource(R.string.settings_import_config),
            subtitle = stringResource(R.string.settings_import_config_sub),
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
        )
        state.backupMessage?.let { failure ->
            FailureNote(
                failure = failure,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }

    state.pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = actions.onCancelImport,
            title = { Text(stringResource(R.string.settings_import_config)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_import_confirm_body))
                    if (pending.smartPlatform != null) {
                        Text(stringResource(R.string.settings_import_has_smart))
                    }
                    // 卡片数是可选的：文件里没带主页卡片时不说"0 张"，只报方案数。
                    val cards = pending.preferences.homeCards
                    Text(
                        if (cards != null) {
                            stringResource(R.string.settings_import_files_cards, pending.profiles.size, cards.size)
                        } else {
                            stringResource(R.string.settings_import_files, pending.profiles.size)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // 导入范围也在这里问 —— 文件选完才知道里面有什么，
                    // 放在选文件之前问是让用户在信息不全的情况下做决定。
                    BackupScopeChips(
                        title = stringResource(R.string.backup_import_scope),
                        selected = state.importCategories,
                        onToggle = actions.onToggleImportCategory,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = actions.onConfirmImport) { Text(stringResource(R.string.settings_import_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = actions.onCancelImport) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** 导出前的范围选择。确认才走文件选择器。 */
@Composable
private fun BackupScopeDialog(
    title: String,
    selected: Set<BackupCategory>,
    onToggle: (BackupCategory, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { BackupScopeChips(title = null, selected = selected, onToggle = onToggle) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_export)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 备份分类的多选。用 chip 不占高度，三项并排一行。 */
@Composable
private fun BackupScopeChips(
    title: String?,
    selected: Set<BackupCategory>,
    onToggle: (BackupCategory, Boolean) -> Unit,
) {
    Column {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BackupCategory.entries.forEach { category ->
                val label = when (category) {
                    BackupCategory.PROFILES -> stringResource(R.string.backup_cat_profiles)
                    BackupCategory.PREFERENCES -> stringResource(R.string.backup_cat_preferences)
                    BackupCategory.SMART_PLATFORM -> stringResource(R.string.backup_cat_smart)
                }
                FilterChip(
                    selected = category in selected,
                    onClick = { onToggle(category, category !in selected) },
                    label = { Text(label) },
                )
            }
        }
    }
}

// ===========================================================================
// 复用的小控件
// ===========================================================================

/**
 * 莫兰迪色板：网格铺开，一眼比色。
 *
 * 为什么不用单选行：这组有十几种，逐个带文字铺开占掉大半屏，而且隔着文字
 * 没法判断两个灰绿到底差在哪。色板把"选颜色"这件事还给它本来的样子。
 *
 * 选中态用**描边 + 对勾**，不靠色块深浅或放大 —— 十几个低饱和度色块并排时，
 * "看起来略大一点"没人认得出来。对勾用 `onPrimary` 之外的对比色：
 * 色块本身就是 primary，勾取表面色才看得见。
 */
@Composable
private fun MorandiSwatchGrid(
    palettes: List<magicau.mtkcontroller.ui.theme.AppPalette>,
    selected: magicau.mtkcontroller.ui.theme.AppPalette,
    onPick: (magicau.mtkcontroller.ui.theme.AppPalette) -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 每行 5 个：手机竖屏下一行 5 个色块约 44dp，手指点得到，
        // 又不至于一行只有一个（那等于回到列表）。
        palettes.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { palette ->
                    val active = palette == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(palette.accent)
                            .border(
                                width = if (active) 2.dp else 1.dp,
                                color = if (active) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape,
                            )
                            .selectable(
                                selected = active,
                                role = Role.RadioButton,
                                onClick = { onPick(palette) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (active) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = stringResource(palette.labelRes),
                                tint = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                // 末行不满 5 个时补空位，否则色块会被 weight 拉成椭圆。
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 自定义莫兰迪强调色：色相 + 浓度两个滑杆，加一个实时预览。
 *
 * ## 为什么只有两个轴
 *
 * 不给亮度。白字压在强调色上，可读性只由**相对亮度**决定，而它同时受色相和浓度
 * 影响 —— 同一个 HSL 亮度下黄绿比蓝紫亮得多。所以亮度不给用户调，由
 * [MorandiAccent] 按可读性反推，用户怎么拖都落在白字能读的区间里。
 *
 * 色相全开（0–360），浓度受限（映射到饱和度 0.03–0.30，实测内置十四种的区间）。
 * 这样"任何一种莫兰迪色"都能调出来，且调不出不是莫兰迪的组合。
 *
 * ## 滑杆用什么
 *
 * 用 Compose 的 `Slider`。之前频率区间那里弃用过 `RangeSlider`，弃用的是
 * **双滑块**那一个类，不是 Slider 本身 —— 别把两件事混成"项目不用 Slider"。
 */
@Composable
private fun CustomAccentRow(
    selected: magicau.mtkcontroller.ui.theme.AppPalette,
    hueDeg: Int,
    concentration: Int,
    onHueChange: (Int) -> Unit,
    onConcentrationChange: (Int) -> Unit,
    onSelectCustom: () -> Unit,
) {
    val custom = selected == magicau.mtkcontroller.ui.theme.AppPalette.CUSTOM
    // 预览色当场算，不读存值 —— 拖动时要跟手。算的是同一个函数，结果与主题一致。
    val preview = remember(hueDeg, concentration) {
        MorandiAccent.fromHueSaturation(hueDeg.toFloat(), concentration / 100f)
    }

    Column(modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 8.dp)) {
        // 选中态也是一颗可选的行：不选自定义时两个滑杆不该看起来能动。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = custom,
                    role = Role.RadioButton,
                    onClick = onSelectCustom,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = custom, onClick = null)
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(preview)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
            Text(
                stringResource(R.string.palette_custom),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        // 色相滑杆的轨道画成彩虹会诱导用户以为能选高饱和色 —— 那不是这个控件能给的。
        // 改成按当前浓度算出来的一排骨块，所见即所得。
        Text(
            stringResource(R.string.custom_hue),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
            value = hueDeg.toFloat(),
            onValueChange = { onHueChange(it.toInt()) },
            valueRange = 0f..360f,
            enabled = custom,
        )
        Text(
            stringResource(R.string.custom_concentration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = concentration.toFloat(),
            onValueChange = { onConcentrationChange(it.toInt()) },
            valueRange = 0f..100f,
            enabled = custom,
        )
        Text(
            // 说清这一档的含义，别让用户以为选了自定义就"什么颜色都能设"。
            stringResource(R.string.custom_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )
    }
}

/** Which unit the interval field is showing. Values are always stored as ms. */
private enum class IntervalUnit(val labelRes: Int, val millis: Long) {
    MILLIS(R.string.unit_millis, 1L),
    SECONDS(R.string.unit_seconds, 1_000L),
}

/**
 * Interval field with a unit picker.
 *
 * Commits on focus loss or IME "done" rather than per keystroke: each commit
 * restarts the re-apply service, so committing as the user types "2000" would
 * restart it four times.
 */
@Composable
private fun IntervalRow(
    intervalMs: Long,
    onCommit: (Long) -> Unit,
) {
    fun format(ms: Long, unit: IntervalUnit): String =
        if (unit == IntervalUnit.MILLIS) ms.toString()
        else (ms.toDouble() / 1000.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }

    var unit by remember { mutableStateOf(IntervalUnit.MILLIS) }
    var text by remember(intervalMs, unit) { mutableStateOf(format(intervalMs, unit)) }

    fun commit() {
        val parsed = text.trim().toDoubleOrNull()
        if (parsed == null || parsed <= 0) {
            text = format(intervalMs, unit)
            return
        }
        onCommit((parsed * unit.millis).toLong())
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 16.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_reapply_interval), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { if (!it.isFocused) commit() },
        )
        SingleChoiceSegmentedButtonRow {
            IntervalUnit.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = unit == entry,
                    onClick = { unit = entry },
                    shape = SegmentedButtonDefaults.itemShape(index, IntervalUnit.entries.size),
                ) { Text(stringResource(entry.labelRes)) }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    detail: String? = null,
    selected: Boolean,
    swatch: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 56.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        if (swatch != null) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(swatch),
            )
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (!detail.isNullOrBlank()) magicau.mtkcontroller.ui.components.FeatureInfo(label, detail)
    }
}
