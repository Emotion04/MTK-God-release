package magicau.mtkcontroller.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import magicau.mtkcontroller.ui.theme.Motion
import magicau.mtkcontroller.ui.theme.MotionSpec

/**
 * 页面转场。**只有一种形态：横向平移，不淡出。**
 *
 * ## 为什么不做淡出
 *
 * 之前是"平移 + 同时淡出"。两者叠加会让页面**边滑边溶解**，看起来像在原地缩小
 * 然后消失，而不是被推开。iOS 的导航转场只有平移、没有淡出，观感上"这一页是被
 * 推到一边的"，方向感明确。
 *
 * 去掉淡出之后，位移本身就是唯一的视觉信号 —— 这也让预测性返回的手势预览
 * 与我们的转场是同一个动作，而不是"系统在拖、我们在淡"。
 *
 * ## 两种关系，两种距离
 *
 * | 关系 | 位移 | 时长 |
 * |---|---|---|
 * | 同级标签切换 | 各挪屏宽的 [Motion.SLIDE_FRACTION] | [Motion.NAVIGATION] |
 * | 进出子页 | 进来的一页整屏，底下那页挪 1/3（视差） | [Motion.DRILL] |
 *
 * 子页用视差而不是两页都整屏：整屏位移会让两页在过渡中同时占满视野，
 * 分不清谁在上面。底下那页只挪三分之一，层级关系一眼可见。
 *
 * ## 为什么不给用户选风格
 *
 * 曾经有个「应用内 / 跟随系统」的二选一，其中"跟随系统"是纯淡入淡出。
 * 那个选项解决的是"自定义位移可能与系统手势预览打架" —— 但现在的平移方向与
 * 返回手势一致，本来就不打架，选项失去意义。**留着一个不该选的选项，比没有更糟**：
 * 用户会以为选错了才导致观感不对。
 */
/**
 * 用哪一套转场。**编译期常量，不是设置项。**
 *
 * ## 为什么不能做成运行时设置
 *
 * 系统的预测性返回预览由 `AndroidManifest.xml` 的
 * `android:enableOnBackInvokedCallback` 控制 —— **那是编译期属性**，
 * 装到手机上之后无法更改。而系统预览与我们的转场会**同时播放**，
 * 两套叠在一起就是"缩小 + 平移"的怪样子。
 *
 * 所以这对选项只能二选一，且必须在打包时定：
 *
 * | [USE_CUSTOM_TRANSITIONS] | manifest 的 flag | 结果 |
 * |---|---|---|
 * | `true`（当前） | 注释掉 | 只有我们的平移，干净 |
 * | `false` | 设为 `"true"` | 只有系统的预测性返回预览 |
 *
 * **切换方式：改这一个常量 + 改 manifest 那一行，两处必须一致。**
 * 不需要动别的代码 —— 这是当初把两套都留着的目的。
 *
 * ## 什么时候该选哪套
 *
 * 选 `false`（系统那套）的条件是：系统预览在目标机型上确实能用，
 * 并且用户更看重"边拖边预览上一页"。否则选 `true` —— 自有转场在任何
 * 机型上表现一致，不依赖 ROM 对预测性返回的实现。
 */
const val USE_CUSTOM_TRANSITIONS = true

object NavMotion {

    fun enter(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
        motion: MotionSpec,
    ): EnterTransition = if (!USE_CUSTOM_TRANSITIONS) {
        EnterTransition.None
    } else if (isTabSwitch(scope)) {
        lateralEnter(scope, motion)
    } else {
        drillEnter(motion)
    }

    fun exit(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
        motion: MotionSpec,
    ): ExitTransition = if (!USE_CUSTOM_TRANSITIONS) {
        ExitTransition.None
    } else if (isTabSwitch(scope)) {
        lateralExit(scope, motion)
    } else {
        drillExit(motion)
    }

    fun popEnter(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
        motion: MotionSpec,
    ): EnterTransition = if (!USE_CUSTOM_TRANSITIONS) {
        EnterTransition.None
    } else if (isTabSwitch(scope)) {
        lateralEnter(scope, motion)
    } else {
        drillPopEnter(motion)
    }

    fun popExit(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
        motion: MotionSpec,
    ): ExitTransition = if (!USE_CUSTOM_TRANSITIONS) {
        ExitTransition.None
    } else if (isTabSwitch(scope)) {
        lateralExit(scope, motion)
    } else {
        drillPopExit(motion)
    }

    /**
     * 两端都是标签 → 同级切换；否则 → 进出子页。
     *
     * 放在一处判断，而不是给每个子页写一遍转场：子页有十七个，逐个覆盖既啰嗦
     * 又容易漏，而"是不是标签"本来就能算出来。
     */
    private fun isTabSwitch(scope: AnimatedContentTransitionScope<NavBackStackEntry>): Boolean =
        isTab(scope.initialState.destination.route) && isTab(scope.targetState.destination.route)

    private fun isTab(route: String?): Boolean = Destination.entries.any { it.route == route }

    // --- 同级标签 -----------------------------------------------------------

    private fun lateralEnter(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
        motion: MotionSpec,
    ): EnterTransition {
        val forward = movesForward(scope)
        return slideInHorizontally(motion.offsetNavigation()) { width ->
            if (forward) (width * Motion.SLIDE_FRACTION).toInt()
            else -(width * Motion.SLIDE_FRACTION).toInt()
        }
    }

    private fun lateralExit(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
        motion: MotionSpec,
    ): ExitTransition {
        val forward = movesForward(scope)
        return slideOutHorizontally(motion.offsetNavigation()) { width ->
            if (forward) -(width * Motion.SLIDE_FRACTION).toInt()
            else (width * Motion.SLIDE_FRACTION).toInt()
        }
    }

    // --- 进出子页 -----------------------------------------------------------

    /** 进来的一页从右侧整屏推入。 */
    private fun drillEnter(motion: MotionSpec): EnterTransition =
        slideInHorizontally(motion.offsetDrill()) { it }

    /** 被压住的那页同时往左让出三分之一 —— 视差，让层级关系可见。 */
    private fun drillExit(motion: MotionSpec): ExitTransition =
        slideOutHorizontally(motion.offsetDrill()) { -it / 3 }

    /** 返回：底下那页从左侧三分之一处归位。 */
    private fun drillPopEnter(motion: MotionSpec): EnterTransition =
        slideInHorizontally(motion.offsetDrill()) { -it / 3 }

    /**
     * 返回：**当前页向右整屏移出。**
     *
     * 这就是"上层界面向右迅速平移"那一条 —— 没有淡出，所以它是干净地被推走，
     * 而不是边滑边溶解。
     */
    private fun drillPopExit(motion: MotionSpec): ExitTransition =
        slideOutHorizontally(motion.offsetDrill()) { it }

    /**
     * 目标标签在当前标签的右边就算"前进"。
     *
     * 拿不到序号时按前进处理 —— 那是最常见的方向，猜错也只是动画方向相反，
     * 不影响功能。
     */
    private fun movesForward(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
    ): Boolean = tabIndexOf(scope.targetState.destination.route) >=
        tabIndexOf(scope.initialState.destination.route)

    private fun tabIndexOf(route: String?): Int =
        Destination.entries.indexOfFirst { it.route == route }.takeIf { it >= 0 } ?: Int.MAX_VALUE
}
