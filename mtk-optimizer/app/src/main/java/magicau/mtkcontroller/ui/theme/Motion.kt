package magicau.mtkcontroller.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntOffset
import magicau.mtkcontroller.R

/**
 * 动效的时间基准。**全应用唯一的时长来源。**
 *
 * ## 为什么要集中
 *
 * 之前时长散在各处：授权卡片写 420/220/160，导航写 260。同一屏里三种节奏，
 * 观感上是"有点乱"但说不清哪里乱 —— 不一致的时长比动画本身更伤质感，
 * 因为它让界面看起来不像一个整体。
 *
 * ## 三档，按"这个变化有多值得注意"分
 *
 * | 档 | 基准 | 用在哪 |
 * |---|---|---|
 * | [FAST] | 160ms | 小元素消失、收起 |
 * | [BASE] | 220ms | 常规出现、展开 |
 * | [NAVIGATION] | 260ms | 页面转场 |
 * | [SLOW] | 420ms | 状态色这类值得注意的变化 |
 *
 * 不用 Material 的 100/200/300 命名，是因为那几个数字之间的差别在真机上分不出来，
 * 而分成四档之后每个都有明确用途，新增动画时不用猜该用哪个。
 *
 * ## 实际时长还要乘用户选的速度
 *
 * 见 [MotionSpeed]。上面这些是**基准值**，真正用出去的是 [MotionSpec] 算出来的。
 *
 * ## 系统"移除动画"会被自动尊重
 *
 * Compose 的 `tween` 走 `MotionDurationScale`，系统把动画时长设为 0 时这些会直接
 * 跳到终点。所以用户真不想看动画，系统层面已经能关，[MotionSpeed] 只是在这个
 * 之外再给一个"快一点/慢一点"的档。
 */
object Motion {

    /** 小元素消失、收起。 */
    const val FAST = 160

    /** 常规出现、展开。 */
    const val BASE = 220

    /** 页面转场。比 [BASE] 略长，因为整屏变化需要更多时间读懂。 */
    const val NAVIGATION = 260

    /**
     * 进出子页（平移）。
     *
     * 比标签切换长：标签是"平移到隔壁"，用户知道自己在哪；进出子页是层级变化，
     * 需要多一点时间看清"从哪来到哪去"。380ms 是 iOS 导航转场的量级。
     */
    const val DRILL = 380

    /** 状态色这类值得注意的变化。慢到能看清"从什么变成了什么"。 */
    const val SLOW = 420

    /**
     * 页面转场的位移距离，占屏宽的比例。
     *
     * 取四分之一而不是整屏：整屏位移观感太重，而且两页会在短时间内同时占据
     * 视野，看不出谁是谁。标签切换用这个比例；子页平移用整屏，因为那更像翻页。
     */
    const val SLIDE_FRACTION = 0.25f
}

/**
 * 动画速度。用户在设置里选。
 *
 * ## 为什么给这个，而不给一个时长滑块
 *
 * 滑块意味着用户能把时长设成任何值，而我们只能在少数几个值上验证观感。
 * 三档都是调过的，选哪档都不会难看。
 *
 * ## 与系统"移除动画"的关系
 *
 * 那是"完全不要动画"，这里是"快一点"。两者不冲突：系统关了动画时，
 * 这一档设成什么都不影响（`MotionDurationScale` 会把时长归零）。
 */
enum class MotionSpeed(val labelRes: Int, val descriptionRes: Int, val scale: Float) {
    /**
     * 不额外缩放。**这是默认档，也是唯一"我们什么都不做"的档。**
     *
     * Compose 的 `tween` 本来就走系统的动画缩放（`MotionDurationScale`），
     * 系统设成 0.5× 时动画就已经是 0.5×。所以这一档的含义是"我们不再叠加"，
     * 而不是"固定 1 倍速" —— 叫它"标准"会让人以为绕过了系统设置。
     */
    SYSTEM(R.string.motion_system, R.string.motion_system_desc, 1.0f),
    FAST(R.string.motion_fast, R.string.motion_fast_desc, 0.6f),
    RELAXED(R.string.motion_relaxed, R.string.motion_relaxed_desc, 1.5f),
    ;

    companion object {
        fun fromName(name: String?): MotionSpeed =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * 按用户选的速度算出来的实际时长。
 *
 * 组件通过 `LocalMotion.current` 取它，**不直接读 [Motion] 的常量** ——
 * 常量是基准值，没算过速度，直接用会无视用户的设置。
 */
data class MotionSpec(val speed: MotionSpeed = MotionSpeed.SYSTEM) {

    val fast: Int get() = scaled(Motion.FAST)
    val base: Int get() = scaled(Motion.BASE)
    val navigation: Int get() = scaled(Motion.NAVIGATION)
    val drill: Int get() = scaled(Motion.DRILL)
    val slow: Int get() = scaled(Motion.SLOW)

    fun <T> fastSpec(): FiniteAnimationSpec<T> = tween(fast, easing = LinearOutSlowInEasing)
    fun <T> baseSpec(): FiniteAnimationSpec<T> = tween(base, easing = LinearOutSlowInEasing)
    fun <T> navigationSpec(): FiniteAnimationSpec<T> =
        tween(navigation, easing = LinearOutSlowInEasing)

    fun <T> drillSpec(): FiniteAnimationSpec<T> =
        tween(drill, easing = LinearOutSlowInEasing)
    fun <T> slowSpec(): FiniteAnimationSpec<T> = tween(slow, easing = LinearOutSlowInEasing)

    fun floatBase(): FiniteAnimationSpec<Float> = tween(base, easing = LinearOutSlowInEasing)

    fun offsetNavigation(): FiniteAnimationSpec<IntOffset> =
        tween(navigation, easing = LinearOutSlowInEasing)

    fun offsetDrill(): FiniteAnimationSpec<IntOffset> =
        tween(drill, easing = LinearOutSlowInEasing)

    /** 下限 80ms：再短就看不出是动画，等于硬切。 */
    private fun scaled(base: Int): Int = (base * speed.scale).toInt().coerceAtLeast(80)
}

/**
 * 当前动效规格。组件从这里取时长。
 *
 * 默认值让主题之外的渲染（预览、测试）也能工作。
 */
val LocalMotion = staticCompositionLocalOf { MotionSpec() }
