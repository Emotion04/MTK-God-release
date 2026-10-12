package magicau.mtkcontroller.ui.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 四个顶层标签的横向容器。
 *
 * ## 为什么用 Pager 而不是继续用 NavHost 的四个目的地
 *
 * 用 NavHost 时切标签是"换一页"，中间没有过渡；用 Pager 之后几个标签像是**并排
 * 连在一起**，左右滑动就能过去，底栏也跟着手指走。这是用户明确要的形态。
 *
 * 子页面（设置里的二级页、实验室等）仍然走 NavHost，压在 Pager 之上 ——
 * 它们是"进入下一层"，不是"平移到隔壁"，两者的返回语义不同。
 *
 * ## `beyondViewportPageCount = 0` 是关键
 *
 * 默认值会预组合相邻页以便滑动时立刻出内容。但那有个具体代价：**两个标签页会
 * 同时处于"可见"状态，而首页和性能页都挂着按可见性开关的采样器** ——
 * 首页每秒读一次内核状态、性能页持续读频率。预组合等于让它们在后台一起跑，
 * 白白耗电。
 *
 * 设成 0 之后只有当前页被组合，采样器的可见性判断自然成立。代价是快速滑动时
 * 目标页要现组合，而这两个页面都不重，实测无感。
 *
 * ## 与底栏的关系
 *
 * 双向：底栏点了标签要滑过去，手动滑了页面底栏要高亮跟着变。两个方向都经过
 * [onTabChange]，避免各自维护一份"当前是哪个标签"而分叉。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabsPager(
    tabs: List<Destination>,
    selected: Destination,
    onTabChange: (Destination) -> Unit,
    /**
     * 外部持有的 Pager 状态。底栏拖动时要直接改它的滚动位置，所以状态必须
     * 提上去 —— 两边各持一份就没法做到"拖多少、页面跟多少"。
     */
    pagerState: androidx.compose.foundation.pager.PagerState,
    modifier: Modifier = Modifier,
    /** [isSelected] 为真表示这一页正显示在屏幕上，周期读取只该在它为真时做。 */
    page: @Composable (Destination, Boolean) -> Unit,
) {
    val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)

    // 外部选中变化 → 滑过去。判断 currentPage 避免自己触发的回调再滑一次
    // （那会在滑动过程中反复打断动画）。
    LaunchedEffect(selectedIndex, tabs.size) {
        if (selectedIndex in tabs.indices && pagerState.currentPage != selectedIndex) {
            pagerState.animateScrollToPage(selectedIndex)
        }
    }

    // 滑动停止 → 通知外部。用 settledPage 而不是 currentPage：后者在滑动过程中
    // 就会变，底栏会在半途闪一下再定下来。
    //
    // **回调必须经 rememberUpdatedState。** 下面这个 LaunchedEffect 的 key 只有
    // pagerState，重组不会重启它，协程里捕获的 `onTabChange` 会一直是第一次组合时
    // 那一份。调用方每次重组都会给一个新 lambda（它读的是组合作用域里的状态），
    // 于是这个协程会一直呼叫旧的那份 —— 里面算出来的 val 全是旧值。
    // 以前能跑是因为调用方恰好只用了状态委托；换成读普通 val 就会静默失效。
    val currentOnTabChange by rememberUpdatedState(onTabChange)
    LaunchedEffect(pagerState) {
        // **第一下不算换页。** snapshotFlow 在开始收集的那一刻会把"当前停在哪一页"
        // 立刻报出来，而那正是这次组合开始时的状态，不是用户刚做的动作。
        //
        // 这一条修的是一个真实 bug：标签容器从子页返回时是**重新组合**的，
        // 此时 Pager 还停在原标签上，这句会把外部刚设好的 selectedTab 覆盖回去 ——
        // 现象是"在子页点底栏只是退回上一层，标签根本没换"。
        var seenInitial = false
        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { index ->
            if (seenInitial) tabs.getOrNull(index)?.let(currentOnTabChange) else seenInitial = true
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        // 预组合相邻一页。
        //
        // 曾经设成 0（只组合当前页），为的是不让相邻页的采样器跟着跑。
        // 但那样滑动时目标页要**现组合**，手上能明显感到卡顿、不跟手 ——
        // 用一个取巧换来的省电，代价是主交互手感变差，不划算。
        //
        // 现在改成 1，改用**可见性门控**采样器：页面照样预组合，
        // 但只有当前显示的那页才做周期读取。见 [page] 的 isSelected 参数。
        beyondViewportPageCount = 1,
    ) { index ->
        // 同上：标签页也要有自己的底，否则滑动时相邻两页的文字会叠。
        androidx.compose.material3.Surface(
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            val tab = tabs.getOrNull(index)
            tab?.let { page(it, index == pagerState.settledPage) }
        }
    }
}
