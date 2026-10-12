package magicau.mtkcontroller.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.di.AppContainer
import magicau.mtkcontroller.domain.model.NavBarStyle
import magicau.mtkcontroller.domain.model.NavBarVisibility
import magicau.mtkcontroller.domain.model.TabRestoreMode
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.CompositionLocalProvider
import magicau.mtkcontroller.di.containerViewModelFactory
import magicau.mtkcontroller.feature.cpu.CpuScreen
import magicau.mtkcontroller.feature.cpu.CpuViewModel
import magicau.mtkcontroller.feature.diag.DiagScreen
import magicau.mtkcontroller.feature.diag.DiagViewModel
import magicau.mtkcontroller.feature.gpu.GpuScreen
import magicau.mtkcontroller.feature.gpu.GpuViewModel
import magicau.mtkcontroller.feature.home.HomeScreen
import magicau.mtkcontroller.feature.home.HomeViewModel
import magicau.mtkcontroller.feature.lab.LabPanelScreen
import magicau.mtkcontroller.feature.lab.LabPanelViewModel
import magicau.mtkcontroller.feature.lab.LabScreen
import magicau.mtkcontroller.feature.lab.LabViewModel
import magicau.mtkcontroller.feature.lab.RawTestScreen
import magicau.mtkcontroller.feature.lab.RawTestViewModel
import magicau.mtkcontroller.feature.notifications.NotificationScreen
import magicau.mtkcontroller.feature.settings.LogScreen
import magicau.mtkcontroller.feature.settings.SettingsScreen
import magicau.mtkcontroller.feature.settings.SettingsViewModel
import magicau.mtkcontroller.feature.tweaks.StatusToolsScreen
import magicau.mtkcontroller.feature.thermal.AppThermalScreen
import magicau.mtkcontroller.feature.thermal.AppThermalViewModel
import magicau.mtkcontroller.feature.tweaks.TweaksScreen
import magicau.mtkcontroller.feature.tweaks.TweaksViewModel
import magicau.mtkcontroller.ui.components.FloatingCapsuleNavBar
import magicau.mtkcontroller.ui.components.SubScreenScaffold
import magicau.mtkcontroller.ui.theme.AppPalette
import androidx.compose.ui.res.stringResource
import magicau.mtkcontroller.R
import magicau.mtkcontroller.feature.settings.ArchiveScreen
import magicau.mtkcontroller.ui.theme.LocalMotion
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf

@Composable
fun AppNavHost(container: AppContainer, modifier: Modifier = Modifier) {
    // The launch tab is a user preference, so the graph waits for it.
    var startRoute by remember { mutableStateOf<String?>(null) }
    // 当前标签。Pager 和底栏共用这一个来源，避免两处各自记一份而分叉。
    //
    // **必须可保存。** 进程重建时导航栈由系统还原（人还停在二级页上），
    // 而这一份若被清空，底栏会亮回默认标签 —— 高亮和用户眼前的页面分家。
    var selectedTab by rememberSaveable { mutableStateOf(Destination.HOME) }
    // 冷启动才用偏好里的启动标签。进程重建时 selectedTab 已还原，再覆盖就把对的冲掉了。
    var tabInitialised by rememberSaveable { mutableStateOf(false) }
    var swipeMode by remember { mutableStateOf(TabSwipeMode.NEAREST) }
    // Pager 状态提在这里：底栏拖动要直接改它的滚动位置。
    val tabPagerState = androidx.compose.foundation.pager.rememberPagerState(
        pageCount = { tabs.size },
    )
    // 一页的宽度 = Pager 的宽度（每页占满）。`PagerState.pageSize` 是 internal，
    // 所以自己量 —— 底栏拖动要按它把"页面单位"换算成像素。
    var pageWidthPx by remember { mutableIntStateOf(0) }
    // 拖动起点。HOLD 模式下没停够要弹回这里，而不是弹到当前位置。
    var draggedFromPage by remember { mutableIntStateOf(0) }
    /** 贯穿一次拖动的滚动会话；null 表示当前没在拖。 */
    var dragSession by remember { mutableStateOf<kotlinx.coroutines.channels.Channel<Float>?>(null) }
    /** 拖动中的绝对页位置（0.0 到 3.0）。拖动期间它才是真源，不是 Pager 的 currentPage。 */
    var dragPosition by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val configured = container.settingsRepository.defaultTabRoute.first()
        startRoute = configured ?: Destination.HOME.route
        // 上次停留的标签就是启动标签 —— 用户从哪离开就从哪回来。
        // 但只在冷启动这么做：tabInitialised 为真说明这份状态是还原来的，
        // 那时 selectedTab 已经是用户真正停留的标签，覆盖它就和眼前看到的分家了。
        if (!tabInitialised) {
            selectedTab = Destination.fromRoute(configured)
            tabInitialised = true
        }
        // 不在这里读 swipeMode —— 见下面的 collect
    }

    val route = startRoute
    if (route == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val navStyleName by container.settingsRepository.navBarStyle
        .collectAsStateWithLifecycle(initialValue = NavBarStyle.COLORFUL.name)
    val navCustomName by container.settingsRepository.navBarCustomPalette
        .collectAsStateWithLifecycle(initialValue = AppPalette.TEAL.name)
    // 底栏的滚动行为。它是**全应用**的一个选择：决定了所有页面的底部留白，
    // 所以在这里读一次、往下传，而不是让每个页面各读一遍。
    val navBarVisibilityName by container.settingsRepository.navBarVisibility
        .collectAsStateWithLifecycle(initialValue = NavBarVisibility.FIXED.name)
    val navBarVisibility = NavBarVisibility.fromName(navBarVisibilityName)
    val navBarClearance = magicau.mtkcontroller.ui.components.clearanceFor(navBarVisibility)

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    /** 切回标签时保留原二级页面，还是回到标签主页。 */
    val tabRestoreName by container.settingsRepository.tabRestoreMode
        .collectAsStateWithLifecycle(initialValue = TabRestoreMode.KEEP.name)
    val tabRestoreMode = TabRestoreMode.fromName(tabRestoreName)

    /**
     * 每个标签最后停住的**整条**子页链（`tabs` 之下的每一层，含参数）。
     *
     * ## 为什么存链而不是存一页
     *
     * `settings/lab` → `settings/lab/panel` 是两层。只记最上面那一层的话，
     * 还原后按返回会从 panel 直接跳回 `tabs`，中间那一层被吞掉 —— 用户会发现自己
     * "少退了一步"，而且说不清为什么。
     *
     * ## 数据从哪儿来
     *
     * `navController.currentBackStack`。它就是权威，所以不自己攒：自己攒要和
     * **系统还原、返回键、屏幕内部发起的 navigate** 三方保持一致，漏一个方向
     * 就会和真实栈分叉，而那种分叉在桌面上看不出来。
     *
     * ## 为什么键是"离开时的那个标签"
     *
     * 子页是从某个标签钻进去的，就记在哪个标签名下。**不能按路由前缀判归属** ——
     * `settings/notifications` 是从工具页钻进去的，前缀却属于设置；按前缀分会把
     * 它记到设置名下，于是从工具页离开再回来，位置丢了。
     * 底栏高亮也用同一个来源（见下面的 `selected`），两边因此不可能不一致。
     *
     * 只在 KEEP 模式下写。手动按返回退回标签主页时清空对应项 ——
     * "退回主页"和"去隔壁看一眼"是两种意图，前者不该被还原。
     */
    // 用 rememberSaveable：旋屏或进程重建后导航栈由系统还原（人还停在二级页），
    // 记忆若被清空，之后切标签就不还原了，与用户所见不一致。
    // Map + List + 枚举都是 Serializable，默认的 saver 存得下。
    var tabStacks by rememberSaveable { mutableStateOf(emptyMap<Destination, List<String>>()) }

    /**
     * 当前返回栈里 `tabs` 之上的那条子页链；空表示停在标签主页。
     *
     * 读 `.value` 而不是 collect：只在切标签那一刻需要它，收进来只会让每次
     * 导航都重组整个 NavHost。
     */
    fun chainAboveTabs(): List<String> {
        val stack = navController.currentBackStack.value
        val start = stack.indexOfFirst { it.destination.route == TABS_ROUTE }
        if (start < 0) return emptyList()
        return stack.drop(start + 1).mapNotNull { it.concreteRoute() }
    }

    /**
     * 栈顶路由，**现取**。
     *
     * 不能用组合时那个 `currentRoute`：本函数会被 `TabsPager` 的
     * `LaunchedEffect(pagerState)` 长期持有（它的 key 只有 pagerState，
     * 重组不会重启它），捕获进来的普通 `val` 会一直是第一次组合时的旧值 ——
     * 于是"人在不在子页上"这个判断永远停在过去。
     * 状态委托（`by`）捕获的是 State 对象、读的时候取新值，所以 selectedTab、
     * tabStacks、tabRestoreName 都能直接用；只有这种算出来的 val 必须现取。
     */
    fun currentRouteNow(): String? =
        navController.currentBackStack.value.lastOrNull()?.destination?.route

    /**
     * 切到某个标签。**底栏点击、左右滑动、拖着底栏滑，三条路都走这里**，
     * 所以两种手势之间不可能有行为差。
     *
     * 语义完全由 [TabRestoreMode] 决定，与手势无关：
     *
     * | 模式 | 切到某个标签 |
     * |---|---|
     * | 保留 | 还原那个标签上次停住的整条子页链；没有就落主页 |
     * | 重置 | 一律回标签主页 |
     */
    fun switchTab(destination: Destination) {
        val keep = TabRestoreMode.fromName(tabRestoreName) == TabRestoreMode.KEEP
        // 保留模式下，点当前标签是**空操作** —— 你要的"上次那一页"就是眼前这一页。
        //
        // 曾经这里是"点当前标签就回主页"，于是同一个设置下点一下和滑一下结果不同：
        // 滑走再滑回来停在原地，点一下却被弹回主页。想回这个标签的主页用系统返回键，
        // 那同时也表示"我退出了这一层"，记忆按同一个规则作废（见下面的 LaunchedEffect）。
        //
        // 重置模式不走这个分支：那一档的语义就是"切就回主页"，点当前标签也该如此，
        // 否则从深层子页出来只剩系统返回键一条路。
        if (keep && destination == selectedTab) return
        val here = currentRouteNow()

        // 先把当前标签的子页链收起来。
        if (keep && here != TABS_ROUTE) {
            val chain = chainAboveTabs()
            if (chain.isNotEmpty()) tabStacks = tabStacks + (selectedTab to chain)
        }

        selectedTab = destination

        // 保存和读取必须用同一个条件 —— 曾经只在保存处判 KEEP，于是用户先在 KEEP 下
        // 设了记忆、再切成 RESET，旧条目还躺着，点标签会把旧二级页还原出来，
        // 与 RESET 的承诺相反。
        val remembered = if (keep) tabStacks[destination] else null
        if (!remembered.isNullOrEmpty()) {
            // 一次 navigate 同时完成"清掉当前子页"和"推入目标页"：分两步做会在中间
            // 多出一帧的栈状态。链上后面的每一层再逐个推，返回键才一层层走得回去。
            //
            // 失败不能静默：concreteRoute() 可能留下占位符、路由也可能改名，
            // 吞掉异常的话用户点标签什么都没发生，连反馈都没有。退回主页并记日志。
            val restored = runCatching {
                navController.navigate(remembered.first()) {
                    popUpTo(TABS_ROUTE) { inclusive = false }
                }
                remembered.drop(1).forEach { navController.navigate(it) }
            }
            if (restored.isFailure) {
                AppLog.w("Nav", "还原二级页失败：$remembered")
                if (here != TABS_ROUTE) {
                    navController.popBackStack(TABS_ROUTE, inclusive = false)
                }
            }
        } else if (here != TABS_ROUTE) {
            navController.popBackStack(TABS_ROUTE, inclusive = false)
        }
    }

    val scope = rememberCoroutineScope()
    // 用 collect 而不是 .first()：设置里改完要立刻生效。
    // 之前读一次就完，用户改了模式却要重启 App 才生效 —— 看起来就像"设置没用"。
    LaunchedEffect(Unit) {
        container.settingsRepository.tabSwipeMode.collect { name ->
            swipeMode = TabSwipeMode.fromName(name)
        }
    }

    // 停在标签主页 = 用户手动退出了一层。这时那个标签记住的位置作废。
    LaunchedEffect(currentRoute) {
        if (currentRoute == TABS_ROUTE) tabStacks = tabStacks - selectedTab
    }

    // The diagnostics screen lives under Settings, so the bar keeps Settings lit.
    // 进子页时底栏仍高亮它所属的标签；回到 tabs 时用 selectedTab。
    //
    // **子页永远不改变高亮：它是压在**当前标签**之上的，不是另一个标签。**
    // 曾经这里按路由前缀判归属，于是从工具页钻进去的 `settings/notifications`
    // 把底栏亮成了设置 —— 用户明明在工具页的流程里，抬头一看标签换了。
    // 而记忆键用的是 selectedTab，两边一套前缀、一套状态，必然分叉。
    // 现在只有一个来源，分叉这一类错从此不可能发生。
    val selected = selectedTab

    // 底栏是否被滚走。只在 AUTO_HIDE 下会变。
    var barHidden by remember { mutableStateOf(false) }
    // 累计滚动量。**不能一见位移就切** —— 手指的每一次微小抖动都会翻转状态，
    // 底栏会抖个不停。攒够一段距离再判定，方向明确。
    var scrollAccumPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(navBarVisibility) {
        // 切回固定模式时把状态复位，否则底栏会停在被藏起来的位置。
        if (navBarVisibility != NavBarVisibility.AUTO_HIDE) barHidden = false
    }
    val navBarScrollConnection = remember(navBarVisibility) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // 只观察，不消费 —— 消费掉的话页面就滚不动了。
                if (navBarVisibility != NavBarVisibility.AUTO_HIDE) return Offset.Zero
                val dy = available.y
                if (dy == 0f) return Offset.Zero
                scrollAccumPx += dy
                // dy < 0 = 内容往上走（人在往深处看）→ 收起底栏把地方让出来。
                if (scrollAccumPx < -NAV_BAR_HIDE_AFTER_PX) {
                    barHidden = true; scrollAccumPx = 0f
                } else if (scrollAccumPx > NAV_BAR_HIDE_AFTER_PX) {
                    // 往回滚 → 底栏回来。用户想切标签时就是先往回带一下。
                    barHidden = false; scrollAccumPx = 0f
                }
                return Offset.Zero
            }
        }
    }

    val barOffsetY by animateDpAsState(
        targetValue = if (barHidden) 120.dp else 0.dp,
        animationSpec = LocalMotion.current.baseSpec(),
        label = "navBarOffset",
    )

    CompositionLocalProvider(
        magicau.mtkcontroller.ui.components.LocalNavBarClearance provides navBarClearance,
    ) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(navBarScrollConnection),
    ) {
        // 转场 lambda 不是 Composable 上下文，读不了 CompositionLocal，
        // 所以在这里取一次再捕获进去。
        val motion = LocalMotion.current

        NavHost(
            navController = navController,
            startDestination = TABS_ROUTE,
            // Edge-to-edge is enabled, so keep content clear of the status bar.
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            // 同级标签切换走横向推移，子页进出也走横向平移（见 NavMotion）。
            // 风格由用户选：SYSTEM 时只用淡入淡出，APP 时按层级选推移方向。
            enterTransition = { NavMotion.enter(this, motion) },
            exitTransition = { NavMotion.exit(this, motion) },
            popEnterTransition = { NavMotion.popEnter(this, motion) },
            popExitTransition = { NavMotion.popExit(this, motion) },
        ) {


            // 四个顶层标签装在一个横向容器里：左右滑动即可切换，底栏跟着走。
            // 子页面仍然各自是独立的导航目的地，压在它之上 ——
            // "平移到隔壁"和"进入下一层"是两种关系，返回语义也不同。
            composable(TABS_ROUTE) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().onSizeChanged { pageWidthPx = it.width },
                ) {
                TabsPager(
                    tabs = tabs,
                    selected = selected,
                    // 滑动和点击同一条路。曾经滑动只改 selectedTab、不收记忆也不还原，
                    // 于是"滑走再滑回来"丢位置，而点一下却不会 —— 同一个动作两种结果。
                    onTabChange = { switchTab(it) },
                    pagerState = tabPagerState,
                ) { tab, isSelected ->
                    when (tab) {
                        Destination.HOME -> {
                            val vm: HomeViewModel = viewModel(
                                factory = containerViewModelFactory { HomeViewModel(container) },
                            )
                            val state by vm.state.collectAsStateWithLifecycle()
                            // 采样器只在首页**真正显示**时跑。
                            // 相邻页现在会预组合（为的是滑动不卡），
                            // 所以"被组合"不等于"看得见" —— 必须看 isSelected。
                            LifecycleResumeEffect(isSelected) {
                                vm.setSamplingActive(isSelected)
                                onPauseOrDispose { vm.setSamplingActive(false) }
                            }
                            HomeScreen(
                                state = state,
                                onOpenTests = { navController.navigate(SubRoutes.TESTS) },
                                onMoveCard = vm::moveCard,
                                onRemoveCard = vm::removeCard,
                                onAddCard = vm::addCard,
                                onResetCards = vm::resetCards,
                                onApplyProfile = vm::applyProfile,
                                onRelease = vm::release,
                                onTouchEnabled = vm::setTouchOptimization,
                                onTouchRate = vm::setTouchRate,
                                onRotationSuggestion = vm::setRotationSuggestion,
                                onThermalBrightness = vm::setThermalBrightness,
                            )
                        }
                        Destination.CPU -> magicau.mtkcontroller.feature.cpu.PerformanceScreen(
                            container,
                            samplingActive = isSelected,
                            onOpenAdvanced = { navController.navigate(SubRoutes.CPU_ADVANCED) },
                        )
                        Destination.TOOLS -> magicau.mtkcontroller.feature.tweaks.TuningScreen { navController.navigate(it) }
                        else -> SettingsTab(container, navController)
                    }
                }
                }
            }

            composable("gpu") {
                magicau.mtkcontroller.feature.cpu.PerformanceScreen(container, initialPage = 1)
            }

            // 「高级设置」二级页。**复用 CPU 标签那一个 ViewModel 实例**：
            // 它要读的选中档位、要写的调速器就在那份状态里，另开一个实例两边会对不上。
            composable(SubRoutes.CPU_ADVANCED) { entry ->
                val owner = remember(entry) {
                    runCatching { navController.getBackStackEntry(TABS_ROUTE) }.getOrDefault(entry)
                }
                val vm: CpuViewModel = viewModel(
                    owner,
                    factory = containerViewModelFactory { CpuViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(
                    stringResource(R.string.cpu_advanced),
                    onBack = navController::popBackStack,
                ) {
                    magicau.mtkcontroller.feature.cpu.CpuAdvancedScreen(
                        state = state,
                        onGovernorChange = vm::setGovernor,
                        onSelectThermalIndex = vm::selectThermalIndex,
                        onToggleThermalDetail = vm::toggleThermalDetail,
                    )
                }
            }

            // 方案不再是独立标签，内容变成性能页顶部的标签。
            // 旧路由保留并重定向，避免深链和"上次停留页"记录指向空页面。
            composable("profiles") {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    // 方案已并入性能页，落到标签容器即可；标签页内容由 Pager 决定。
                    navController.navigate(TABS_ROUTE) {
                        popUpTo(TABS_ROUTE) { inclusive = true }
                    }
                }
            }

            // 设置分类的二级页。一条路由装全部分类，分类名走路径参数 ——
            // 每加一个分类只改 SettingsCategory，不动这里。
            composable("${SubRoutes.SETTINGS_CATEGORY}/{${SubRoutes.SETTINGS_CATEGORY_ARG}}") { entry ->
                val category = magicau.mtkcontroller.feature.settings.SettingsCategory.fromName(
                    entry.arguments?.getString(SubRoutes.SETTINGS_CATEGORY_ARG),
                )
                val vm: SettingsViewModel = viewModel(
                    factory = containerViewModelFactory { SettingsViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(category.labelRes), onBack = navController::popBackStack) {
                    magicau.mtkcontroller.feature.settings.SettingsCategoryScreen(
                        category = category,
                        state = state,
                        actions = settingsActions(vm, navController),
                    )
                }
            }

            // The sub-screens are pure content; the nav host owns their top bar
            // so the centred title is identical across all three.
            composable(SubRoutes.TESTS) {
                val vm: CpuViewModel = viewModel(factory = containerViewModelFactory { CpuViewModel(container) })
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(R.string.sub_tests), onBack = navController::popBackStack) {
                    magicau.mtkcontroller.feature.cpu.CpuTestsScreen(
                        state, vm::recheckPowerHal, vm::probe, vm::stopProbe, vm::probeAll,
                        vm::exportProbe, vm::collectRomConfigs, vm::setRange, vm::release,
                        onOpenFeatureTrial = { navController.navigate(SubRoutes.FEATURE_TRIAL) },
                    )
                }
            }

            composable(SubRoutes.FEATURE_TRIAL) {
                SubScreenScaffold(stringResource(R.string.sub_feature_trial), onBack = navController::popBackStack) {
                    magicau.mtkcontroller.feature.tweaks.FeatureTrialScreen(container)
                }
            }

        composable(SubRoutes.STATUS) {
            SubScreenScaffold(stringResource(R.string.sub_status_tools), onBack = navController::popBackStack) {
                StatusToolsScreen()
            }
        }

            listOf(SubRoutes.TWEAKS, SubRoutes.CONFIG, SubRoutes.MAINTENANCE, SubRoutes.GAME, SubRoutes.UNAVAILABLE).forEach { toolsRoute -> composable(toolsRoute) {
                val vm: TweaksViewModel = viewModel(
                    factory = containerViewModelFactory { TweaksViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                val (title, group) = when (toolsRoute) {
                    SubRoutes.CONFIG -> R.string.sub_config to "config"
                    SubRoutes.MAINTENANCE -> R.string.sub_maintenance to "maintenance"
                    SubRoutes.GAME -> R.string.sub_game to "game"
                    SubRoutes.UNAVAILABLE -> R.string.sub_unavailable to "unavailable"
                    else -> R.string.sub_display to "display"
                }
                SubScreenScaffold(stringResource(title), onBack = navController::popBackStack) {
                    TweaksScreen(
                        state = state,
                        onTouch = vm::setTouch,
                        onVivo = { feature, restore, option, force -> vm.setVivo(feature, restore, option, force) },
                        onCollectReport = vm::collectTweakReport,
                        onExportReport = vm::exportTweakReport,
                        onDisplay = vm::setDisplay,
                        onMaintenance = vm::runMaintenance,
                        onRefreshVivo = vm::refreshVivoCapabilities,
                        onSetPhaseOffset = vm::setPhaseOffset,
                        onRestorePhaseOffsets = vm::restorePhaseOffsets,
                        onFakeBattery = vm::fakeBattery,
                        onApplyGameBoost = vm::applyGameBoost,
                        onRestoreGameBoost = vm::restoreGameBoost,
                        onSelectGameBoostApp = vm::selectGameBoostApp,
                        onForegroundApp = vm::refreshForegroundApp,
                        group = group,
                        // 进入"游戏与画面实验"时先读一次相位偏移的当前值 ——
                        // 之后无论写什么，恢复都写回这里读到的那些。
                        onEnterGame = if (group == "game") ({ vm.snapshotPhaseOffsets(); vm.refreshGameBoostStatus() }) else null,
                        onTouchRate = vm::setTouchRate,
                        onTouchItem = vm::setTouchItem,
                        onVivoUnsupported = vm::reportPerfUnsupported,
                        onFrameInterpolation = vm::setFrameInterpolation,
                        onFrameInterpolationSr = vm::setFrameInterpolationSr,
                        onRotationSuggestion = vm::setRotationSuggestion,
                        onThermalBrightness = vm::setThermalBrightness,
                        onRequestPermission = vm::requestPermission,
                        onOpenNotifications = { navController.navigate(SubRoutes.NOTIFICATIONS) },
                    )
                }
            } }

            listOf(SubRoutes.SMART, SubRoutes.SMART_BACKUP).forEach { smartRoute -> composable(smartRoute) {
                val vm: TweaksViewModel = viewModel(factory = containerViewModelFactory { TweaksViewModel(container) })
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(
                    stringResource(
                        if (smartRoute == SubRoutes.SMART) R.string.sub_smart else R.string.sub_smart_backup,
                    ),
                    onBack = navController::popBackStack,
                ) {
                    if (smartRoute == SubRoutes.SMART) magicau.mtkcontroller.feature.tweaks.SmartPlatformScreen(state, vm) { navController.navigate(SubRoutes.SMART_BACKUP) }
                    else magicau.mtkcontroller.feature.tweaks.SmartBackupScreen(state, vm)
                }
            } }

            composable(SubRoutes.NOTIFICATIONS) {
                SubScreenScaffold(stringResource(R.string.sub_notifications), onBack = navController::popBackStack) {
                    NotificationScreen()
                }
            }

            composable(SubRoutes.DIAGNOSTICS) {
                val vm: DiagViewModel = viewModel(
                    factory = containerViewModelFactory { DiagViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(R.string.sub_diagnostics), onBack = navController::popBackStack) {
                    DiagScreen(
                        state = state,
                        onRefresh = vm::refresh,
                        onRequestPermission = vm::requestPermission,
                        onVerify = vm::verifyApplied,
                    )
                }
            }

            composable(SubRoutes.LAB) {
                val vm: LabViewModel = viewModel(
                    factory = containerViewModelFactory { LabViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(R.string.sub_lab), onBack = navController::popBackStack) {
                    LabScreen(
                        state = state,
                        onOpenPanel = { navController.navigate(SubRoutes.LAB_PANEL) },
                        onOpenRawTest = { navController.navigate(SubRoutes.LAB_RAW) },
                        onValueChange = vm::setValue,
                        onHoldChange = vm::setHold,
                        onTest = vm::test,
                        onSweep = { feature, stop -> vm.sweep(feature, stop) },
                        onResetAll = vm::resetAll,
                        onClearObservations = vm::clearObservations,
                    )
                }
            }

            composable(SubRoutes.LAB_RAW) {
                val vm: RawTestViewModel = viewModel(
                    factory = containerViewModelFactory { RawTestViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(R.string.sub_lab_raw), onBack = navController::popBackStack) {
                    RawTestScreen(
                        state = state,
                        onPolicy = vm::setPolicy,
                        onRow = vm::setRow,
                        onHold = vm::setHold,
                        onSend = vm::send,
                        onReset = vm::reset,
                    )
                }
            }

            composable(SubRoutes.APP_THERMAL) {
                val vm: AppThermalViewModel = viewModel(
                    factory = containerViewModelFactory { AppThermalViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(R.string.sub_app_thermal), onBack = navController::popBackStack) {
                    AppThermalScreen(
                        state = state,
                        onSetRunning = vm::setRunning,
                        onShowAdd = vm::showAdd,
                        onHideAdd = vm::hideAdd,
                        onDraftPkg = vm::setDraftPkg,
                        onDraftIndex = vm::setDraftIndex,
                        onAdd = vm::addRule,
                        onRemove = vm::removeRule,
                    )
                }
            }

            composable(SubRoutes.LAB_PANEL) {
                val vm: LabPanelViewModel = viewModel(
                    factory = containerViewModelFactory { LabPanelViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(R.string.sub_lab_panel), onBack = navController::popBackStack) {
                    LabPanelScreen(state = state, onRefresh = vm::refresh)
                }
            }

            composable(SubRoutes.ARCHIVE) {
                val vm: SettingsViewModel = viewModel(
                    factory = containerViewModelFactory { SettingsViewModel(container) },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(
                    stringResource(R.string.archive_title),
                    onBack = navController::popBackStack,
                ) {
                    ArchiveScreen(
                        freqCompanionLabel = state.freqCompanionLabel,
                        onSetFreqCompanion = vm::setFreqCompanion,
                        onOpenTests = { navController.navigate(SubRoutes.TESTS) },
                        onOpenFeatureTrial = { navController.navigate(SubRoutes.FEATURE_TRIAL) },
                    )
                }
            }
            composable(SubRoutes.EXPORT) {
                val vm: magicau.mtkcontroller.feature.diag.ExportViewModel = viewModel(
                    factory = containerViewModelFactory {
                        magicau.mtkcontroller.feature.diag.ExportViewModel(container)
                    },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                val context = androidx.compose.ui.platform.LocalContext.current
                val saveLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/zip"),
                ) { uri -> uri?.let(vm::saveTo) }
                SubScreenScaffold(stringResource(R.string.sub_export), onBack = navController::popBackStack) {
                    magicau.mtkcontroller.feature.diag.ExportScreen(
                        state = state,
                        onSetInclude = vm::setInclude,
                        onToggleRecording = vm::toggleRecording,
                        onSetRecordingLevel = vm::setRecordingLevel,
                        onDiscardRecovered = vm::discardRecovered,
                        onExport = vm::export,
                        onShare = {
                            // 分享可能因为系统没有可接收的应用而失败，
                            // 所以界面上同时给了"保存到文件"和"复制路径"两条退路。
                            vm.fileForShare()?.let { file ->
                                runCatching {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, "${context.packageName}.reports", file,
                                    )
                                    val send = android.content.Intent(
                                        android.content.Intent.ACTION_SEND,
                                    ).apply {
                                        type = "application/zip"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        android.content.Intent.createChooser(send, null),
                                    )
                                }
                            }
                        },
                        onSave = { saveLauncher.launch("diagnostic.zip") },
                        onClearLogs = vm::clearLogs,
                        onDismissMessage = vm::dismissMessage,
                    )
                }
            }
            composable(SubRoutes.LOGS) {
                val vm: SettingsViewModel = viewModel(
                    factory = containerViewModelFactory { SettingsViewModel(container) },
                )
                val reportExportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("text/plain"),
                ) { uri -> uri?.let(vm::exportReport) }
                val state by vm.state.collectAsStateWithLifecycle()
                SubScreenScaffold(stringResource(R.string.sub_logs), onBack = navController::popBackStack) {
                    // Probe once on entry so "复制报告" carries the device data
                    // rather than only the log lines.
                    LaunchedEffect(Unit) { vm.refreshReport() }
                    LogScreen(
                        state = state,
                        onClear = vm::clearLogEntries,
                        onCopyReport = vm::copyReport,
                        onExportReport = { reportExportLauncher.launch(vm.reportFileName()) },
                    )
                }
            }
        }

        FloatingCapsuleNavBar(
            destinations = Destination.entries,
            selectedRoute = selected.route,
            style = NavBarStyle.fromName(navStyleName),
            customColor = AppPalette.fromName(navCustomName).accent,
            // 点击和滑动同一条路，行为只由 TabRestoreMode 决定。
            onSelect = { switchTab(it) },
            // 胶囊位置从 Pager 推导，不再是底栏自己的一份状态。
            // 拖动期间读自己记的位置：Pager 的 currentPage 要到会话结束才更新，
            // 直接读它会让胶囊在整段拖动里纹丝不动。
            // **必须是 lambda，不能在这儿求值。** 这个表达式读的是 Pager 的
            // snapshot 状态，每帧都变；在 AppNavHost 的组合作用域里读它，
            // 一帧就让整个 NavHost（含四个标签页的全部内容）失效重组。
            // 传进去、让它在底栏内部读，失效范围才缩得下来。
            pageOffset = {
                if (dragSession != null) {
                    dragPosition
                } else {
                    tabPagerState.currentPage + tabPagerState.currentPageOffsetFraction
                }
            },
            onDragStart = {
                draggedFromPage = tabPagerState.currentPage
                // 自己累计"拖到第几页"，而不是从 Pager 反推。
                //
                // 为什么必须自己存：`scrollBy` 只移动像素，**逻辑页码要等会话
                // 结束才更新** —— 拖动全程 currentPage 都停在原页，松手就弹回去。
                // 官方为此提供了 updateCurrentPage(page, offset)，在会话内立即
                // 更新页码，由我们喂绝对位置而不是增量。
                dragPosition = tabPagerState.currentPage.toFloat()
                val channel = kotlinx.coroutines.channels.Channel<Float>(
                    kotlinx.coroutines.channels.Channel.CONFLATED,
                )
                dragSession = channel
                scope.launch {
                    tabPagerState.scroll {
                        // 这版 Compose 的 ScrollScope 只有 scrollBy，没有
                        // updateCurrentPage —— 所以 Pager 的逻辑页码要到会话结束
                        // 才更新。没关系：拖动期间**我们自己记的 dragPosition 才是真源**，
                        // 胶囊和松手都读它，不读 Pager 的 currentPage。
                        for (delta in channel) scrollBy(delta)
                    }
                }
            },
            onDragBy = { pageDelta ->
                if (pageWidthPx > 0) {
                    dragPosition = (dragPosition + pageDelta)
                        .coerceIn(0f, tabs.lastIndex.toFloat())
                    // 会话里要的是**像素增量**，不是绝对页位置 —— 位置由我们累计，
                    // Pager 只负责把画面移过去。
                    dragSession?.trySend(pageDelta * pageWidthPx)
                }
            },
            onSettle = { _, heldStill ->
                dragSession?.close()
                dragSession = null
                val nearest = dragPosition.roundToInt().coerceIn(0, tabs.lastIndex)
                val destination = when (swipeMode) {
                    TabSwipeMode.NEAREST -> nearest
                    // 停留后切换：没停够就弹回**拖动起点**，不是当前位置 ——
                    // 否则拖一半松手会停在中间那页。
                    TabSwipeMode.HOLD -> if (heldStill) nearest else draggedFromPage
                }
                scope.launch { tabPagerState.animateScrollToPage(destination) }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 收起时整条滑到屏幕外。位移量给足，别留一条边露在底下。
                .offset(y = barOffsetY)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        )
    }
    }
}

/**
 * 攒够多少纵向位移才翻转底栏的显隐。
 *
 * 太小会在手指的抖动里来回翻；太大则要划很久才有反应。
 * 取 ~56dp：一次有意的滚动轻松超过，噪声远达不到。
 */
private const val NAV_BAR_HIDE_AFTER_PX = 56f

/** 四个顶层标签共用的容器路由。标签本身不再是导航目的地。 */
private const val TABS_ROUTE = "tabs"

/** 标签顺序。底栏与 Pager 都用它，避免两处各排一次而错位。 */
private val tabs: List<Destination> = Destination.entries

/**
 * 把 ViewModel 和导航控制器接成设置页要用的那一包回调。
 *
 * **只在这里接线。** 一级页和二级页都收这个对象，所以加一项设置时
 * 需要改的只有：`SettingsActions` 加一个字段 + 这里加一行 + 真正用它那个二级页面。
 * 原来三十多个具名参数要在三个调用点各抄一遍，加一项要改三处，还容易漏。
 */
private fun settingsActions(
    vm: SettingsViewModel,
    navController: androidx.navigation.NavHostController,
) = magicau.mtkcontroller.feature.settings.SettingsActions(
    onSetDefaultTab = vm::setDefaultTab,
    onSetApplyMode = vm::setApplyMode,
    onSetReapplyInterval = vm::setReapplyIntervalMs,
    onSetFreqRangeMode = vm::setFreqRangeMode,
    onSetTouchBackend = vm::setTouchBackend,
    onSetBrightnessBackend = vm::setBrightnessBackend,
    onSetThemeMode = vm::setThemeMode,
    onSetMotionSpeed = vm::setMotionSpeed,
    onSetShapeStyle = vm::setShapeStyle,
    onSetPalette = vm::setPalette,
    onSetNavBarStyle = vm::setNavBarStyle,
    onSetNavBarCustomPalette = vm::setNavBarCustomPalette,
    onSetTabSwipeMode = vm::setTabSwipeMode,
    onSetCpuLayoutMode = vm::setCpuLayoutMode,
    onSetRangeControlMode = vm::setRangeControlMode,
    onSetNavBarVisibility = vm::setNavBarVisibility,
    onSetTabRestoreMode = vm::setTabRestoreMode,
    onSetCustomHue = vm::setCustomHue,
    onSetCustomConcentration = vm::setCustomConcentration,
    onOpenDisplayTweaks = { navController.navigate(SubRoutes.TWEAKS) },
    onOpenLab = { navController.navigate(SubRoutes.LAB) },
    onOpenAppThermal = { navController.navigate(SubRoutes.APP_THERMAL) },
    onOpenTests = { navController.navigate(SubRoutes.TESTS) },
    onOpenArchive = { navController.navigate(SubRoutes.ARCHIVE) },
    onOpenExport = { navController.navigate(SubRoutes.EXPORT) },
    onOpenNotifications = { navController.navigate(SubRoutes.NOTIFICATIONS) },
    onOpenDiagnostics = { navController.navigate(SubRoutes.DIAGNOSTICS) },
    onOpenLogs = { navController.navigate(SubRoutes.LOGS) },
    onSetLogLevel = vm::setLogLevel,
    onOpenSmartBackup = { navController.navigate(SubRoutes.SMART_BACKUP) },
    onExportBackup = vm::exportBackup,
    onPrepareImport = vm::prepareImport,
    onConfirmImport = vm::confirmImport,
    onCancelImport = vm::cancelImport,
    onToggleExportCategory = vm::toggleExportCategory,
    onToggleImportCategory = vm::toggleImportCategory,
    onRequestAuth = vm::requestAuth,
)

/** 设置标签的内容：一级分类列表。 */
@Composable
private fun SettingsTab(
    container: AppContainer,
    navController: androidx.navigation.NavHostController,
) {
    val vm: SettingsViewModel = viewModel(
        factory = containerViewModelFactory { SettingsViewModel(container) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        actions = settingsActions(vm, navController),
        onOpenCategory = { category ->
            navController.navigate(SubRoutes.settingsCategory(category))
        },
    )
}

/**
 * 当前条目的**具体**路由：`settings/cat/GENERAL`，不是 `settings/cat/{category}`。
 *
 * `destination.route` 给的是模式，`navigate()` 不认模式 —— 传进去会当成一个字面路径，
 * 匹配不上任何 composable。所以要拿参数把占位符填回去。
 *
 * 参数取不到的（理论上不该发生）就原样留着占位符，调用方靠 `runCatching` 兜住。
 */
private fun androidx.navigation.NavBackStackEntry.concreteRoute(): String? {
    val pattern = destination.route ?: return null
    val args = arguments ?: return pattern
    var out = pattern
    for (key in args.keySet()) {
        val value = args[key] ?: continue
        out = out.replace("{$key}", value.toString())
    }
    return out
}
