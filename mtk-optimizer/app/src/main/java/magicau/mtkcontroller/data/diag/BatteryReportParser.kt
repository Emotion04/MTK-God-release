package magicau.mtkcontroller.data.diag

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 从 vivo bugreport 里抽取电池与充电信息。
 *
 * vivo 的 bugreport 是个 zip，里面的正文是纯文本。这里只做**只读解析**：解出关心的几段，
 * 不修改原始文件，也不把结论说成"性能提升"。
 *
 * 为什么值得做：本机的 CPU 频率上下限读不出来（device-vivo-v2430a.md §2），
 * "充电为什么变慢"这类问题只能靠 bugreport 里的充电链路数据间接看。
 */
object BatteryReportParser {

    data class Result(
        val present: Boolean,
        val message: String,
        val lines: List<String> = emptyList(),
    )

    /** 解析一个 bugreport zip；path 由用户通过系统文件选择器给出。 */
    fun parse(path: String): Result {
        val file = File(path)
        if (!file.isFile || !file.canRead()) return Result(false, "无法读取该文件")
        if (file.length() < 1024) return Result(false, "文件太小，不像 bugreport")

        return try {
            ZipFile(file).use { zip ->
                val entry = pickEntry(zip) ?: return Result(false, "zip 里没有文本条目")
                val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                if (text.length < 4096) return Result(false, "主文本条目太小，可能不是完整 bugreport")
                Result(true, "已解析 ${entry.name}（${text.length / 1024} KiB）", extract(text))
            }
        } catch (e: Exception) {
            Result(false, "解析失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 从 content:// 流解析。走文件选择器时拿不到真实路径，所以这里直接吃流。
     * 调用方负责关闭输入流。
     */
    fun parseStream(input: java.io.InputStream, name: String): String {
        // 流不能倒回，先落一个临时文件再按 zip 读；bugreport 可能有几十 MB，
        // 落盘位置用 cacheDir，由系统回收。
        val tmp = File.createTempFile("bugreport", ".zip")
        return try {
            tmp.outputStream().use { input.copyTo(it) }
            val result = parse(tmp.absolutePath)
            if (result.present) {
                result.lines.joinToString("\n").let { "$name\n\n$it" }
            } else {
                "$name\n\n解析未通过：${result.message}"
            }
        } catch (e: Exception) {
            "$name\n\n解析失败：${e.javaClass.simpleName}: ${e.message}"
        } finally {
            tmp.delete()
        }
    }

    private fun pickEntry(zip: ZipFile): ZipEntry? = zip.entries().toList()
        .filter { it.name.endsWith(".txt", true) && !it.isDirectory }
        .maxByOrNull { it.size }

    private fun extract(text: String): List<String> {
        val out = mutableListOf<String>()
        fun section(header: String, limit: Int = 40, filter: (String) -> Boolean = { true }) {
            val start = text.indexOf(header)
            if (start < 0) return
            val slice = text.substring(start, minOf(start + 8000, text.length))
            val lines = slice.lineSequence().drop(1).takeWhile { l ->
                l.isNotBlank() && !l.contains("------") && !Regex("^\\s{0,4}[A-Z][A-Za-z ]{4,}:\\s*$").matches(l)
            }.filter(filter).take(limit).toList()
            out += lines
        }

        out += "[电池状态]"
        section("Battery History:")
        out += "[当前电池]"
        section("Battery Service native dump")
        section("Charge Counter")
        out += "[充电链路]"
        // vivo 的电池服务段；找不到就说明该 ROM 的段名不同，如实报告。
        val vivoCharge = Regex("(?m)^\\s*(Max charging current|Max charging voltage|Charging current state|Charge type)[^\\n]*")
            .findAll(text).map { it.value.trim() }.distinct().take(12).toList()
        if (vivoCharge.isEmpty()) out += "未在 bugreport 里找到 Max charging current / Charge type 等字段"
        else out += vivoCharge

        out += "[双电芯与温度]"
        val cells = Regex("(?m)^\\s*(batt_temp|Battery temp|Temperature)\\s*[:=][^\\n]*")
            .findAll(text).map { it.value.trim() }.distinct().take(10).toList()
        out += if (cells.isEmpty()) listOf("未找到温度字段") else cells

        return out
    }
}
