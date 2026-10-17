package magicau.mtkcontroller.data.lab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.data.privilege.PrivilegeManager

/**
 * 一个可选的值，附带上它的来历。
 *
 * @param note 这个值是干什么的。**没有依据就写"未知"** —— 不许编。
 */
data class LabChoice(
    val value: Int,
    val label: String,
    val note: String,
)

/**
 * 温控策略索引的**真实**来源：`/vendor/etc/thermal/` 下有哪些
 * thermal_policy_NN.conf 文件。
 *
 * 以前这里写死 `range = 0..19`，是猜的：这台机器只有 13 份文件，
 * 滑杆却有 20 个位置，多出来 7 个是空号（拖上去 HAL 找不到文件，什么也不发生）。
 *
 * **这个类是实验室对 [magicau.mtkcontroller.data.thermal.ThermalPolicyReader] 的适配层，
 * 不是第二份实现。** 列目录曾经在这里各写了一遍，于是同一个 `/vendor/etc/thermal`
 * 有两种枚举代码、两种失败语义 —— Reader 用 `listOrNull` 把"没列成"和"没有文件"
 * 分开（`CpuViewModel` 靠这个区别报错），这里用 `list` 直接把 null 吞成空列表。
 * 现在只保留 Reader 那一份。
 *
 * **这个类只回答"设备上有哪些档位"这一个问题**，不回答"每一档是什么意思" ——
 * 那是分析结论，会随 ROM 版本变化，属于文档而不是代码。各档含义见
 * `docs/thermal/facts.md`；配置来源见 `docs/thermal/configs/README.md`。
 *
 * 曾经有一份随包的 `assets/thermal-policies.md` 讲各档含义，已删：
 * 它自己标着"可能已过时"，而设备上的 conf 现读现解才是不过时的那一份。
 */
object ThermalPolicyCatalog {

    /** 列目录，取出真实存在的索引。读不到就返回空，让调用方按"未知"处理。 */
    suspend fun indices(): List<Int> =
        magicau.mtkcontroller.data.thermal.ThermalPolicyReader.availableIndices()

    /**
     * 给界面用的选项表。**索引就是文件名里那个编号，不重新编号** ——
     * 这样 ROM 升级新增文件会自动出现，编号也和任何外部资料对得上。
     */
    suspend fun choices(): List<LabChoice> = indices().map { index ->
        LabChoice(
            value = index,
            label = index.toString().padStart(2, '0'),
            // 含义不进代码：只有编号是可验证的事实，"这一档干什么"会随 ROM 变。
            note = "",
        )
    }

    /**
     * 默认选中哪一档。
     *
     * 不写"某个索引最好" —— 那是实测结论，ROM 升级就可能失效。取
     * [PREFERRED] 里第一个设备上真有的，纯粹为了首屏不落在明显压低保的那一档。
     */
    val PREFERRED: List<Int> = listOf(1, 8, 9, 10, 11, 2, 3, 5, 7, 12, 13)

    fun initialIndex(available: List<Int>): Int =
        PREFERRED.firstOrNull { it in available } ?: available.firstOrNull() ?: 0

    /**
     * 读当前生效的皮肤节流阈值，例如 "41/44/46/48/60/80 °C"。
     *
     * 来源：`dumpsys thermalservice` 的 `Temperature static thresholds from HAL:`
     * 里 `mName=SKIN` 那一行的 `mHotThrottlingThresholds`。
     * **这是唯一无 root 能看出"当前生效哪一组温控策略"的通道** ——
     * 阈值由当前加载的那份 conf 的 `[thermalhal-threshold]` 段决定，
     * 换策略它立刻变。
     *
     * 只读数值，不在这里做"这组阈值对应哪份文件"的判断 —— 那是会随 ROM
     * 变的结论，属于 `docs/thermal/facts.md` 而不是代码。
     */
    suspend fun skinThresholdText(): String? = withContext(Dispatchers.IO) {
        val out = PrivilegeManager.exec("dumpsys thermalservice") ?: return@withContext null
        val line = out.lineSequence().firstOrNull { "mName=SKIN" in it } ?: return@withContext null
        // 不用正则：Kotlin 普通字符串里 \[ 是非法转义，而这里只是取两个定界符之间的一段。
        val marker = "mHotThrottlingThresholds=["
        val from = line.indexOf(marker)
        if (from < 0) return@withContext null
        val bodyStart = from + marker.length
        val to = line.indexOf(']', bodyStart)
        if (to < 0) return@withContext null
        val hot = line.substring(bodyStart, to)
        val degrees = hot.split(',').mapNotNull { token ->
            token.trim().toFloatOrNull()?.takeIf { it.isFinite() }?.toInt()
        }
        if (degrees.isEmpty()) null else degrees.joinToString("/") + " °C"
    }
}
