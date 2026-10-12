package magicau.mtkcontroller.data.privilege

import magicau.mtkcontroller.data.diag.DaemonExec

/**
 * 编译前的只读状态。
 *
 * 为什么需要它：`cmd package compile` 会覆盖系统 bg-dexopt-job 的结果，系统稍后也可能
 * 再覆盖回来。反复编译不会损坏产物（odex 原地覆盖，不追加副本），但会白干 —— 发热和
 * 存储都付出了，最后谁跑得晚谁定级别。
 *
 * 还有一类更隐蔽的：`speed-profile` 依赖使用记录文件。文件不存在或过期时它会静默退化，
 * 表现是"编译成功、读播没变化"，跟 ROM 没关系。所以这里连 profile 一起读。
 *
 * 只读，不推断"值不值得编译"，只报现在是什么状态。
 */
object CompileStatus {

    /** @param odexKb / vdexKb null 表示该文件不存在；profileBytes null 表示读不到。 */
    data class State(
        val apkPath: String?,
        val odexKb: Long?,
        val vdexKb: Long?,
        val apkMtimeMs: Long?,
        val odexMtimeMs: Long?,
        val profileBytes: Long?,
        val note: String,
    ) {
        /** odex 比 apk 新，说明编译发生在最近一次安装或更新之后。 */
        val compiledAfterUpdate: Boolean
            get() = odexMtimeMs != null && (apkMtimeMs == null || odexMtimeMs >= apkMtimeMs)

        /** odex 比 apk 旧，应用更新过但没重新编译。 */
        val stale: Boolean get() = apkMtimeMs != null && odexMtimeMs != null && apkMtimeMs > odexMtimeMs

        val hasCompiledArtifact: Boolean get() = odexKb != null
    }

    /** @param pkg 目标应用。读不到任何东西时照样返回一个 State，note 说明卡在哪。 */
    suspend fun check(pkg: String): State {
        val apk = DaemonExec.runArgv(listOf("/system/bin/pm", "path", pkg))
        val apkPath = if (apk != null && apk.exit == 0) {
            apk.stdout.lineSequence().firstOrNull { it.startsWith("package:") }
                ?.removePrefix("package:")?.trim()
        } else null
        if (apkPath == null) {
            return State(null, null, null, null, null, null,
                if (apk == null) "命令没能送到用户服务，请确认 Shizuku 已连接" else "读不到 $pkg 的安装路径")
        }
        val dir = apkPath.substringBeforeLast('/')

        // 一次 stat 拿到 oat 目录下所有产物的体积和修改时间。用 glob 而不是 find -exec，
        // 老版本 toybox 的 find 不一定支持 -exec ... +。
        val oat = DaemonExec.run(
            "for f in '${dir.replace("'", "'\\''")}'/oat/*/*; do [ -f \"\$f\" ] && stat -c '%s %Y %n' \"\$f\"; done 2>/dev/null")
        var odexKb: Long? = null
        var vdexKb: Long? = null
        var odexMtimeMs: Long? = null
        for (line in oat?.stdout?.lineSequence().orEmpty()) {
            val parts = line.trim().split(' ')
            if (parts.size < 3) continue
            val size = parts[0].toLongOrNull() ?: continue
            val mtimeSec = parts[1].toLongOrNull() ?: continue
            val path = parts[2]
            when {
                path.endsWith(".odex") -> {
                    odexKb = (odexKb ?: 0L) + size / 1024
                    odexMtimeMs = maxOf(odexMtimeMs ?: 0L, mtimeSec * 1000)
                }
                path.endsWith(".vdex") -> vdexKb = (vdexKb ?: 0L) + size / 1024
            }
        }

        val apkMtime = DaemonExec.runArgv(listOf("/system/bin/stat", "-c", "%Y", apkPath))
        val apkMtimeMs = apkMtime?.stdout?.trim()?.toLongOrNull()?.times(1000)

        // 使用记录文件位置随版本变过；两处都试，读不到不猜测。
        val profile = DaemonExec.run(
            "stat -c %s '/data/misc/profiles/cur/0/${pkg.replace("'", "'\\''")}/primary.prof' 2>/dev/null || " +
                "stat -c %s '/data/misc/profiles/ref/${pkg.replace("'", "'\\''")}/primary.prof' 2>/dev/null")
        val profileBytes = profile?.stdout?.trim()?.toLongOrNull()

        return State(apkPath, odexKb, vdexKb, apkMtimeMs, odexMtimeMs, profileBytes,
            note(odexKb, vdexKb, apkMtimeMs, odexMtimeMs, profileBytes))
    }

    /** 一行结论。只讲状态，不讲"建议编译"或"不建议"。 */
    internal fun note(
        odexKb: Long?,
        vdexKb: Long?,
        apkMtimeMs: Long?,
        odexMtimeMs: Long?,
        profileBytes: Long?,
    ): String {
        val parts = mutableListOf<String>()
        val odex = odexKb?.let { "odex ${it / 1024} MiB" }
        val vdex = vdexKb?.takeIf { it > 0 }?.let { "vdex ${it / 1024} MiB" }
        listOfNotNull(odex, vdex).takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(" · ") }
        when {
            // vdex 是验证信息，odex 才是编译出来的机器码。两者都没有才算没编译。
            odexKb == null && vdexKb == null -> parts += "没有编译产物"
            odexKb == null -> parts += "只有验证产物，未编译"
            odexMtimeMs == null -> parts += "读不到编译时间"
            apkMtimeMs == null -> parts += "读不到应用更新时间"
            apkMtimeMs > odexMtimeMs -> parts += "编译早于上次应用更新，可能已失效"
            else -> parts += "编译晚于上次应用更新"
        }
        parts += when {
            profileBytes == null -> "使用记录读不到"
            profileBytes == 0L -> "使用记录为空"
            else -> "使用记录 ${profileBytes / 1024} KiB"
        }
        return parts.joinToString(" · ")
    }
}
