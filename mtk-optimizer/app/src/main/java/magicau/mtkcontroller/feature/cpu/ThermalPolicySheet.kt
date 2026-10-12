package magicau.mtkcontroller.feature.cpu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.thermal.ThermalPolicy
import magicau.mtkcontroller.ui.theme.LocalShapeStyle

/**
 * 温控档位的说明页。
 *
 * ## 为什么单独做一页而不是几行文字
 *
 * 一档温控策略里，每个核簇各有一条"温度 → 频率"的阶梯，三簇并列就是一张表。
 * 用文字横着铺开（`70°C→2.0G  75°C→1.8G …`）三行之后就没法对照了 ——
 * 用户真正想知道的是"**同一温度下三个核簇分别被压到多少**"，那是列与列的比较，
 * 只有表格能一眼看出来。
 *
 * ## 为什么可以不用 MD 规范
 *
 * 这里不是常规控件，是**数据呈现**。数据表要的是列对齐、数值等宽、冷热可辨，
 * 这些比"统一用 bodyMedium"重要。所以温度用等宽字体、频率条按冷热上色，
 * 都不跟排版规范走。
 */
@Composable
fun ThermalPolicySheet(
    indices: List<Int>,
    policies: Map<Int, ThermalPolicy>,
    full: Map<Int, String>,
    currentIndex: Int?,
    showDetail: Boolean,
    clusterLabel: @Composable (Int) -> String,
    onToggleDetail: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // 全屏：一张表最少三列，压在默认对话宽度里每列只剩几十像素，数字会折行。
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.thermal_policy_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                    // 参数原文的开关放在这里，而不是只在 CPU 页卡片上 ——
                    // 用户是**看表格时**才想核对原始字段的，让他退出去再开一次是多余的往返。
                    TextButton(onClick = onToggleDetail) {
                        Text(stringResource(
                            if (showDetail) R.string.thermal_detail_hide
                            else R.string.thermal_detail_show,
                        ))
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(indices) { index ->
                        val policy = policies[index]
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "%02d".format(index),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (index == currentIndex) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                if (index == currentIndex) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.thermal_current),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            if (policy == null) {
                                Text(
                                    stringResource(R.string.thermal_read_failed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else if (policy.decodeError != null) {
                                Text(
                                    policy.decodeError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else {
                                ThermalLadderTable(policy, clusterLabel)
                                ThermalPolicyNotes(policy, showDetail, full[index])
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一张"温度 × 核簇"的表。
 *
 * 横轴是核簇，纵轴是温度。每格画一根横条：长度是该频率相对本档最高频率的比例，
 * 颜色按温度从冷到暖 —— 一眼能看出"温度越高、条越短、色越暖"。
 */
@Composable
fun ThermalLadderTable(
    policy: ThermalPolicy,
    clusterLabel: @Composable (Int) -> String,
    modifier: Modifier = Modifier,
) {
    val ladders = policy.cpuLadders.toSortedMap()
    if (ladders.isEmpty()) {
        Text(
            stringResource(R.string.thermal_no_pressure),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    // 表说的哪一路温度。**必须写在表头上方** —— 不同档位会用完全不同的传感器，
    // 两路的摄氏度不可比，读者得知道眼下这张表是那一路的。
    ladders.values.firstNotNullOfOrNull { it.sensor }?.let { sensor ->
        Text(
            stringResource(R.string.thermal_sensor_from, sensor),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val thresholds = ladders.values.flatMap { it.thresholdsC }.distinct().sorted()
    // 分母取全档最高频率，各列的比例才可比 —— 用各自的最高频当分母的话，
    // 每个核簇都会有一格是满的，看不出哪个核簇被压得更狠。
    val maxFreq = ladders.values.flatMap { it.freqsMHz }.maxOrNull()?.coerceAtLeast(1) ?: 1
    val minT = thresholds.firstOrNull() ?: 0
    val maxT = thresholds.lastOrNull() ?: 1
    val cool = MaterialTheme.colorScheme.tertiary
    val hot = MaterialTheme.colorScheme.error

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // 表头
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Spacer(Modifier.width(LABEL_COLUMN_WIDTH))
            ladders.keys.forEach { key ->
                Text(
                    clusterLabel(key),
                    modifier = Modifier.weight(1f).padding(horizontal = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }

        thresholds.forEach { temp ->
            val heat = if (maxT > minT) (temp - minT).toFloat() / (maxT - minT) else 0f
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$temp°C",
                    modifier = Modifier.width(LABEL_COLUMN_WIDTH),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ladders.forEach { (_, ladder) ->
                    val at = ladder.thresholdsC.indexOf(temp)
                    val mhz = ladder.freqsMHz.getOrNull(at)
                    FreqCell(mhz, maxFreq, lerp(cool, hot, heat), Modifier.weight(1f))
                }
            }
        }
    }
}

/** 温度那一列的固定宽度。等宽字体下 "105°C" 是最宽的情况。 */
private val LABEL_COLUMN_WIDTH = 46.dp

/**
 * 表格里的一格：一根按比例伸缩的横条 + 数值。
 *
 * 条和数字都要有 —— 只有条看不出具体数值，只有数字看不出相对高低，
 * 而这一页存在的意义正是"同一温度下比出高低"。
 */
@Composable
private fun FreqCell(
    mhz: Int?,
    maxFreqMHz: Int,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (mhz == null) {
            Text(
                "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }
        val fraction = (mhz.toFloat() / maxFreqMHz).coerceIn(0.05f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(LocalShapeStyle.current.chip),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(LocalShapeStyle.current.chip)
                    .background(barColor),
            )
        }
        Text(
            if (mhz >= 1000) "%.2fG".format(mhz / 1000.0) else "${mhz}M",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            // 高频用一个更实的颜色，低频退到次要色，扫一眼就知道哪边高。
            color = if (fraction > 0.7f) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 表格下面的一小段说明：稳态温度目标、传感器、限流、隔离这些非"温度→频率"的参数。
 *
 * [showDetail] 打开时换成参数原文 —— 那是给排错用的，平时不摆出来。
 */
@Composable
private fun ThermalPolicyNotes(
    policy: ThermalPolicy,
    showDetail: Boolean,
    fullText: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 传感器名必须给：各档用的是不同的温度源，65°C 与 91°C 是两个完全不同的
        // 物理点，不写清楚就会被当成同一个量直接比较。
        policy.skinControlSensor?.let { sensor ->
            Text(
                stringResource(R.string.thermal_sensor_from, sensor),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        policy.steadyTargetTJC?.let { target ->
            // 上下界一起给，否则这个数会被读成一条限制线。
            val lo = policy.minTargetTJC
            val hi = policy.maxTargetTJC
            Text(
                if (lo != null && hi != null) {
                    stringResource(R.string.thermal_target_temp_range, target, lo, hi)
                } else {
                    stringResource(R.string.thermal_target_temp, target)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (policy.isolateEnabled == true && policy.isolateThresholdsC.isNotEmpty()) {
            Text(
                stringResource(R.string.thermal_isolate_from, policy.isolateThresholdsC.joinToString("/")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        policy.gpuLadder?.takeIf { !it.isEmpty }?.let { ladder ->
            Text(
                stringResource(
                    R.string.thermal_gpu_ladder,
                    ladder.thresholdsC.zip(ladder.freqsMHz)
                        .joinToString("  ") { (c, m) -> "$c°C→${m}M" },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (policy.chargerThresholdsC.isNotEmpty() && policy.chargerCurrentsMA.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.thermal_charger_limit,
                    policy.chargerThresholdsC.zip(policy.chargerCurrentsMA)
                        .joinToString("  ") { (c, ma) -> "$c°C→${ma}mA" },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showDetail && !fullText.isNullOrBlank()) {
            Text(
                fullText,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
