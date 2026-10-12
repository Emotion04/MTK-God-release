package magicau.mtkcontroller.data.tweaks

import androidx.annotation.StringRes
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.diag.DaemonExec

/**
 * SurfaceFlinger 相位偏移：让 App 动笔的时刻相对 vsync 节拍器提前或延后多少。
 *
 * **它是个时序微调，不是"越快越好"的开关。** 调对了 App 能赶上这一帧；调错了反而赶不上，
 * 直接掉帧。而且 `getprop` 读到的值不等于生效 —— 只有掉不掉帧能告诉你，没有客观读数。
 *
 * 原值一旦被写 0 覆盖就找不回来，所以每个键都先 `getprop` 存原值再写，恢复时写回原值
 * 而不是写 0。
 *
 * `debug.` 前缀的属性**不跨重启保留**（只有 `persist.` 才保留），所以"本次开机有效"；
 * 这同时也意味着重启本身就是恢复。
 * 各家的 SurfaceFlinger 可能改过或删掉这些属性，写了不报错也不做事 —— 写后必须 getprop
 * 回读，读不回就是本机不认。
 *
 * **读写作必须经 [DaemonExec] 送到 Shizuku 用户服务里跑**：`setprop` 只接受 shell/root，
 * 在 App 进程里调必然失败，所以早先这个功能整个是失效的。
 */
object PhaseOffset {

    data class Key(val prop: String, @StringRes val labelRes: Int, val maxNs: Long)

    val keys = listOf(
        Key("debug.sf.early_phase_offset_ns", R.string.phase_offset_global, 6_000_000),
        Key("debug.sf.early_app_phase_offset_ns", R.string.phase_offset_app, 6_000_000),
        Key("debug.sf.early_gl_phase_offset_ns", R.string.phase_offset_gl, 6_000_000),
        Key("debug.sf.high_fps_early_phase_offset_ns", R.string.phase_offset_high_fps, 6_100_000),
    )

    /** 所有键的原值；读不到的记为 null，恢复时跳过而不是写空。 */
    suspend fun snapshot(): Map<String, String?> = keys.associate { it.prop to read(it.prop) }

    private suspend fun read(prop: String): String? {
        val out = DaemonExec.runArgv(listOf("/system/bin/getprop", prop)) ?: return null
        if (out.exit != 0) return null
        // getprop 对未设置的属性返回空串。空串与"读不到"要分开：空串可以写，读不到不能猜。
        return out.stdout.trim()
    }

    private suspend fun write(prop: String, value: String): Boolean {
        val out = DaemonExec.runArgv(listOf("/system/bin/setprop", prop, value)) ?: return false
        if (out.exit != 0) return false
        return read(prop) == value
    }

    /** 写一个键；返回人可读的结果。 */
    suspend fun set(prop: String, ns: Long): String {
        val key = keys.firstOrNull { it.prop == prop } ?: return "未知相位偏移键"
        if (ns !in 0..key.maxNs) return "取值需在 0–${key.maxNs} 之间"
        val value = ns.toString()
        if (!write(prop, value)) {
            return "写入未回读确认，本机可能不支持该属性"
        }
        return "$prop 已设为 $value。时序微调，可能反而掉帧；重启后恢复"
    }

    /** 归零：写 0，并明说这不等于 ROM 的原始默认值。 */
    suspend fun zero(prop: String): String = set(prop, 0) +
        "\n0 表示不启用相位偏移，不一定是本 ROM 的原始默认值"

    /** 按快照逐项恢复，跳过读不到原值的键。 */
    suspend fun restore(snapshot: Map<String, String?>): String {
        val results = mutableListOf<String>()
        for ((prop, original) in snapshot) {
            if (original == null) {
                results += "$prop: 原值不可读，跳过"
                continue
            }
            results += if (write(prop, original)) "$prop 已恢复 $original" else "$prop 恢复失败（目标 $original）"
        }
        return results.joinToString("\n")
    }
}
