package magicau.mtkcontroller.data.diag

import android.content.Context
import android.net.Uri
import magicau.mtkcontroller.data.cpu.CpuControl
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.log.LogFileStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the diagnostic archive the user sends back.
 *
 * ## Package layout
 *
 * ```
 * README.txt        field notes, English, no prose beyond what is needed
 * manifest.json     what is in this archive and why
 * report.json       device state: baseline + current + deltas
 * baseline.json     the clean first-run state, verbatim (when available)
 * logs/             raw log lines for the requested window, one file per day
 * ```
 *
 * ## Two products, one entry point
 *
 * [Kind.ADAPTATION] answers "what is this device like" and is small enough to
 * send by default. [Kind.DEBUG] answers "what happened just now" and carries
 * the raw log window the user selected. They share this assembler, the log
 * store, and the export path — but they are separate payloads, because a
 * report that is always megabytes cannot also be the one users send casually.
 */
class ReportExporter(
    private val context: Context,
    private val probe: CapabilityProbe,
    private val cpuControl: CpuControl,
    private val baseline: BaselineSnapshot,
) {

    enum class Kind { ADAPTATION, DEBUG }

    /** What the archive should contain. */
    data class Request(
        val kind: Kind,
        /** Log window in milliseconds back from now. Null = no logs (adaptation default). */
        val logWindowMs: Long? = null,
        val includeAllLogs: Boolean = false,
    )

    data class Result(val file: File, val bytes: Long, val entries: Int)

    suspend fun build(request: Request): Result {
        val stamp = FILE_STAMP.format(Date())
        val name = when (request.kind) {
            Kind.ADAPTATION -> "mtk-god-adaptation-$stamp.zip"
            Kind.DEBUG -> "mtk-god-debug-$stamp.zip"
        }
        val outFile = File(cacheDir(), name)
        var entries = 0

        ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
            zip.put("README.txt", readme())
            zip.put("report.json", buildReport(request).toString(2))
            entries += 2

            baseline.read()?.let {
                zip.put("baseline.json", it.toString(2))
                entries++
            }

            val logs = selectedLogs(request)
            logs.forEach { file ->
                file.inputStream().buffered().use { input ->
                    zip.putNextEntry(ZipEntry("logs/${file.name}"))
                    input.copyTo(zip)
                    zip.closeEntry()
                    entries++
                }
            }

            zip.put("manifest.json", manifest(request, logs).toString(2))
            entries++
        }

        AppLog.i(TAG, "报告已生成 ${outFile.name}（${entries} 项，${outFile.length()} 字节）")
        return Result(outFile, outFile.length(), entries)
    }

    /** Write to the location the user picked in the system file dialog. */
    fun saveTo(uri: Uri, source: File): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } != null
    }.getOrElse {
        AppLog.w(TAG, "写入所选位置失败: ${it.javaClass.simpleName}")
        false
    }

    /** Whole-disk path, for the "copy path" fallback when sharing is unavailable. */
    fun pathOf(source: File): String = source.absolutePath

    fun cleanup(source: File) {
        runCatching { source.delete() }
    }

    // --- assembly ----------------------------------------------------------

    private fun buildReport(request: Request): JSONObject {
        val current = DeviceSnapshot.capture(context, probeReport(), cpuControl.status.value)
        val base = baseline.read()
        return JSONObject().apply {
            put("schema", SCHEMA)
            put("kind", request.kind.name.lowercase())
            put("generated_at", ISO.format(Date()))
            put("generated_at_ms", System.currentTimeMillis())
            put("current", current)
            put("baseline", base ?: JSONObject.NULL)
            put("baseline_missing_reason", if (base == null) {
                "not captured yet; it is written on first launch"
            } else JSONObject.NULL)
            put("delta", delta(base, current))
            put("log_window", JSONObject().apply {
                put("window_ms", request.logWindowMs ?: JSONObject.NULL)
                put("include_all", request.includeAllLogs)
                put("files_included", selectedLogs(request).size)
                put("retention_cap_bytes", LogFileStore.maxBytes)
                put("log_dir", LogFileStore.directoryPath(context))
            })
        }
    }

    /**
     * What differs between the clean state and now.
     *
     * Only the sections that can legitimately change are compared — timestamps
     * always differ and would drown the signal. The point of this block is to
     * let a developer see at a glance whether a feature was already broken out
     * of the box or was broken by something this app did.
     */
    private fun delta(base: JSONObject?, current: JSONObject): JSONObject {
        if (base == null) return JSONObject().put("available", false)
        val changed = JSONArray()
        COMPARED_SECTIONS.forEach { section ->
            val a = base.optJSONObject(section) ?: return@forEach
            val b = current.optJSONObject(section) ?: return@forEach
            if (a.toString() != b.toString()) {
                changed.put(JSONObject().apply {
                    put("section", section)
                    put("baseline", a)
                    put("current", b)
                })
            }
        }
        return JSONObject().apply {
            put("available", true)
            put("changed_sections", changed)
            put("changed_count", changed.length())
        }
    }

    private fun manifest(request: Request, logs: List<File>): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("kind", request.kind.name.lowercase())
        put("generated_at", ISO.format(Date()))
        put("app_version", magicau.mtkcontroller.BuildConfig.VERSION_NAME)
        put("app_version_code", magicau.mtkcontroller.BuildConfig.VERSION_CODE)
        put("contents", JSONArray().apply {
            put("README.txt — field notes")
            put("report.json — device state, baseline, delta, log window")
            if (baseline.exists()) put("baseline.json — clean first-run state")
            logs.forEach { put("logs/${it.name} — raw log lines") }
        })
        put("note", "field names are stable; parse by name, not by position")
    }

    private fun readme(): String = """
        MTK God diagnostic archive
        ==========================

        This archive is read by tooling. Field names are stable English
        identifiers; parse by name.

        report.json
          schema            archive format version
          kind              adaptation | debug
          generated_at      when this archive was produced
          current           device state at export time
          baseline          device state on first launch, before this app changed
                            anything. Null when the app never completed a first run.
          delta             sections that differ between baseline and current
          log_window        which log range is included, and the retention cap

        Every device section uses the same shape in both objects, so baseline and
        current can be compared field by field.

        report.json.device
          manufacturer / model / device / board / hardware
          soc_manufacturer / soc_model
          android_release / api_level / build_id / security_patch / abis

        report.json.privilege
          mode              NONE | AVAILABLE | ADB | ROOT — how much the app may do
          uid               effective uid (2000 = shell, 0 = root)
          permission_granted / binder_alive / shizuku_version

        report.json.powerhal
          service_found     whether the vendor power service is reachable
          interface_reported  descriptor the binder actually returned
          acquire_opcode / release_opcode  transaction codes used

        report.json.cpu.clusters[]
          policy / index / cpus / hw_min_khz / hw_max_khz
          available_freq_khz / governor / governor_writable / driver

        report.json.features[]
          category / label / ok / detail — one entry per capability check.
          ok=false with detail is the direct answer to "what does not work here".

        report.json.telemetry_endpoints[]
          path / access — ok | absent | unreadable. A monitoring section that is
          empty on this device is almost always explained here.

        logs/*.jsonl
          One JSON object per line: ts, iso, lvl, tag, msg, pid, tid
          Errors and crashes additionally carry a "stack" field.
          Lines are append-only and survive a crash or a process kill.
    """.trimIndent()

    // --- helpers -----------------------------------------------------------

    private fun probeReport(): CapabilityReport? = lastProbe

    /** Cached so a report build does not re-run the whole probe. */
    @Volatile
    private var lastProbe: CapabilityReport? = null

    suspend fun refreshProbe() {
        lastProbe = runCatching { probe.probe() }.getOrNull()
    }

    private fun selectedLogs(request: Request): List<File> {
        val all = LogFileStore.listFiles(context)
        if (request.includeAllLogs) return all
        val window = request.logWindowMs ?: return emptyList()
        val cutoff = System.currentTimeMillis() - window
        // 按文件切分即可：日志按天轮转，一天的粒度足够回答"最近几小时发生了什么"，
        // 而逐行过滤要在导出路径上读完整份日志，代价不值得。
        return all.filter { it.lastModified() >= cutoff }
    }

    private fun cacheDir(): File = File(context.cacheDir, "reports").apply { mkdirs() }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }

    private companion object {
        const val TAG = "ReportExporter"
        const val SCHEMA = 1
        val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
        val FILE_STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)

        /** Sections compared for the delta. Timestamps are excluded on purpose. */
        val COMPARED_SECTIONS = listOf("privilege", "powerhal", "cpu", "gpu", "features")
    }
}
