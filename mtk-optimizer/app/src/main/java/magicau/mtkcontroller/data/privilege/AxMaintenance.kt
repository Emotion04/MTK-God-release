package magicau.mtkcontroller.data.privilege

import java.io.File
import magicau.mtkcontroller.data.diag.ProbeProcess

/** 有界的一次性任务；不执行用户输入的 shell 文本。 */
object AxMaintenance {
    fun run(kind: String, pkg: String): String {
        require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(pkg)) { "应用包名无效" }
        val path = ProbeProcess.run(listOf("/system/bin/pm", "path", pkg), 5000, 16000)
        check(path.exit == 0 && !path.truncated && path.error == null && !path.timedOut) { "无法读取应用安装路径" }
        val files = path.output.lineSequence().filter { it.startsWith("package:") }.map { File(it.removePrefix("package:")) }.toList()
        check(files.isNotEmpty() && files.all { it.isFile && it.path.endsWith(".apk") }) { "应用不存在或 APK 不可读" }
        return when (kind) {
            "compile" -> {
                val result = ProbeProcess.run(listOf("/system/bin/cmd", "package", "compile", "-m", "speed-profile", pkg), 120000, 12000)
                if (result.exit == 0 && !result.timedOut && result.error == null && result.output.contains("Success", true))
                    "OK:编译请求完成；提速效果因应用而异"
                else "ERROR:编译未确认完成\n${result.render()}"
            }
            "warm" -> {
                // `dumpsys battery set` 能让框架报告"已插电"，而实际没有。此时读 APK
                // 是白费 192 MiB / 60 秒，所以先交叉核对内核电源节点再决定。
                val battery = ProbeProcess.run(listOf("/system/bin/dumpsys", "battery"), 2500, 12000)
                check(battery.exit == 0 && Regex("(?m)^\\s*(AC|USB|Wireless|Dock) powered: true\\s*$").containsMatchIn(battery.output)) { "请连接电源后预热" }
                check(!FakeBattery.isFaked()) {
                    "框架报告已插电，但内核电源节点显示未连接 —— 可能是假充电生效中。请先关闭假充电，或真正连接电源"
                }
                var remaining = 192L * 1024 * 1024
                var loaded = 0L
                val deadline = System.nanoTime() + 60_000_000_000L
                for (file in files) {
                    if (remaining <= 0) break
                    val bytes = minOf(file.length(), remaining)
                    val count = (bytes + 4095) / 4096
                    val ms = ((deadline - System.nanoTime()) / 1_000_000).coerceAtMost(15000)
                    check(ms > 0) { "预热超时" }
                    val result = ProbeProcess.run(listOf("/system/bin/dd", "if=${file.path}", "of=/dev/null", "bs=4096", "count=$count"), ms, 2048)
                    check(result.exit == 0 && !result.timedOut && result.error == null) { "预热读取失败" }
                    remaining -= count * 4096; loaded += bytes
                }
                "OK:已读取 ${loaded / 1024 / 1024} MiB；缓存可被系统回收"
            }
            else -> "ERROR:未知任务"
        }
    }
}
