package magicau.mtkcontroller.data.privilege

import magicau.mtkcontroller.data.diag.DaemonExec

/**
 * `cmd package compile -m speed-profile` 的进度轮询。
 *
 * 系统编译是个黑盒：命令要等最长 120 秒，界面只能显示一个转圈。这里在做一次只读快照，
 * 把 `dex2oat` 进程数、目标应用的 `.odex`/.vdex 变化摊开给用户看。
 *
 * 只读：不改编译流程，也**不据此推断编译是否成功** —— 成功与否仍以 `cmd package compile`
 * 的返回为准。进度只是"现在大概在干什么"。
 *
 * 命令经 [DaemonExec] 送到 Shizuku 用户服务里跑：`/data/app` 下的 oat 目录对普通应用
 * 不可读，在 App 进程里读只会全部是空。
 */
object CompileProgress {

    data class Snapshot(val dex2oatCount: Int, val odexSizeKb: Long?, val note: String)

    /** @param pkg 目标应用；传空则只报告全系统 dex2oat 数量。 */
    suspend fun snapshot(pkg: String): Snapshot {
        val ps = DaemonExec.run("ps -A | grep -c dex2oat")
        val dex2oat = ps?.stdout?.trim()?.toIntOrNull() ?: -1

        val apk = DaemonExec.runArgv(listOf("/system/bin/pm", "path", pkg))
        val dir = if (apk != null && apk.exit == 0) {
            apk.stdout.lineSequence().firstOrNull { it.startsWith("package:") }
                ?.removePrefix("package:")?.substringBeforeLast('/')
        } else null
        val odexKb = if (dir != null && pkg.isNotBlank()) {
            DaemonExec.run("for f in '${dir.replace("'", "'\\''")}'/oat/*; do [ -f \"\$f\" ] && du -k \"\$f\"; done 2>/dev/null | awk '{s+=\$1} END{print s+0}'")
                ?.stdout?.trim()?.toLongOrNull()
        } else null

        val note = when {
            ps == null -> "命令没能送到用户服务，请确认 Shizuku 已连接"
            dex2oat < 0 -> "读不到 dex2oat 进程数；进度只按 odex 体积变化判断"
            dex2oat == 0 -> "当前没有 dex2oat 在跑；可能已结束，也可能还没到编译阶段"
            else -> "有 $dex2oat 个 dex2oat 在跑"
        }
        return Snapshot(dex2oat, odexKb, note)
    }
}
