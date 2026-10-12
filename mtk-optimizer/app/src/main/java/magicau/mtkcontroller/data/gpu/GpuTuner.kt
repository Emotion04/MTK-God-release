package magicau.mtkcontroller.data.gpu

import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.sysfs.Sysfs
import magicau.mtkcontroller.domain.model.GpuChannel
import magicau.mtkcontroller.domain.model.GpuInfo

/**
 * Applies GPU frequency limits on whichever interface the device exposes.
 *
 * MTK's GPU DVFS is firmware-managed, so vendor power services can overwrite
 * what we set. The foreground service re-applies periodically for that reason.
 */
class GpuTuner {

    data class Outcome(
        val success: Boolean,
        val message: Message,
        /** 原始错误，默认收起、可复制。界面用 `FailureNote` 展示。 */
        val detail: String? = null,
    )

    /**
     * Devfreq min/max as they were before we touched them.
     *
     * One slot is enough — sysfs is a single global state, so at most one
     * devfreq range can be in force. Keying this by handle would be wrong: the
     * GPU handler is not a lease but a persistent marker
     * (`ProfileRepository.gpuHandler` holds 0 while a range is applied), so it
     * cannot tell two applies apart.
     *
     * The values are sampled on write, never taken from a scan. `GpuInfo` min/max
     * is a cache that may already reflect a ceiling we set ourselves; restoring
     * from it would keep our own lock in place and still report success.
     */
    @Volatile
    private var devfreqBaseline: Pair<Long?, Long?>? = null

    /**
     * @param minKhz desired floor; null = leave alone
     * @param maxKhz desired ceiling; null = leave alone
     */
    suspend fun apply(gpu: GpuInfo, minKhz: Long?, maxKhz: Long?): Outcome {
        if (!gpu.controllable) return Outcome(false, Message(R.string.gpu_no_interface))
        if (minKhz == null && maxKhz == null) return Outcome(false, Message(R.string.gpu_no_freq_set))

        return when (gpu.channel) {
            GpuChannel.DEVFREQ -> applyDevfreq(gpu, minKhz, maxKhz)
            GpuChannel.GPUFREQ_V2 -> applyGpufreqV2(gpu, minKhz, maxKhz)
            GpuChannel.GPUFREQ_V1 -> applyGpufreqV1(gpu, minKhz, maxKhz)
            GpuChannel.NONE -> Outcome(false, Message(R.string.gpu_no_interface))
        }
    }

    private suspend fun applyDevfreq(gpu: GpuInfo, minKhz: Long?, maxKhz: Long?): Outcome {
        val failures = mutableListOf<String>()
        val root = GpuScanner.DEVFREQ_ROOT

        // Snapshot both nodes before the first write, including the one that is
        // being left alone — a range we did not set still has to be restored.
        // Kept on failure: whatever is there then is the original state anyway,
        // and dropping it would strand a range an earlier apply did set.
        if (devfreqBaseline == null) {
            devfreqBaseline = Sysfs.read("$root/min_freq")?.toLongOrNull() to
                Sysfs.read("$root/max_freq")?.toLongOrNull()
        }

        // Raise the ceiling before lowering the floor, so min <= max always holds.
        // 写失败时把服务端原话收着 —— 那是判断"该去哪儿修"的唯一依据。
        val writeErrors = mutableListOf<String>()
        maxKhz?.let {
            Sysfs.writeOrNull("$root/max_freq", it.toString())?.let { e ->
                failures += "max_freq"; writeErrors += e
            }
        }
        minKhz?.let {
            Sysfs.writeOrNull("$root/min_freq", it.toString())?.let { e ->
                failures += "min_freq"; writeErrors += e
            }
        }
        return if (failures.isEmpty()) {
            Outcome(true, Message(R.string.gpu_range_applied))
        } else {
            Outcome(
                false,
                Message(R.string.gpu_write_failed, listOf(failures.joinToString())),
                detail = writeErrors.joinToString("\n").ifBlank { null },
            )
        }
    }

    private suspend fun applyGpufreqV2(gpu: GpuInfo, minKhz: Long?, maxKhz: Long?): Outcome {
        // v2 fixes a single OPP; without a range concept we pin the ceiling
        // (or the floor when no ceiling was given) and report that.
        val target = maxKhz ?: minKhz ?: return Outcome(false, Message(R.string.gpu_no_freq_set))
        val index = gpu.oppIndexFor(target)
            ?: return Outcome(false, Message(R.string.gpu_opp_map_failed, listOf(target / 1000)))
        val writeError = Sysfs.writeOrNull(GpuScanner.GPUFREQ_V2_FIX_OPP, index.toString())
        return if (writeError == null) {
            Outcome(true, Message(R.string.gpu_locked_opp, listOf(index, target / 1000)))
        } else {
            Outcome(false, Message(R.string.gpu_write_opp_failed), detail = writeError)
        }
    }

    private suspend fun applyGpufreqV1(gpu: GpuInfo, minKhz: Long?, maxKhz: Long?): Outcome {
        val target = maxKhz ?: minKhz ?: return Outcome(false, Message(R.string.gpu_no_freq_set))
        val writeError = Sysfs.writeOrNull(GpuScanner.GPUFREQ_V1_OPP_FREQ, target.toString())
        return if (writeError == null) {
            Outcome(true, Message(R.string.gpu_locked_mhz, listOf(target / 1000)))
        } else {
            Outcome(false, Message(R.string.gpu_write_opp_freq_failed), detail = writeError)
        }
    }

    /** Release: undo whatever channel-specific lock we applied. */
    suspend fun release(gpu: GpuInfo, handler: Int): Outcome {
        return when (gpu.channel) {
            GpuChannel.GPUFREQ_V2 -> {
                val writeError = Sysfs.writeOrNull(GpuScanner.GPUFREQ_V2_FIX_OPP, "-1")
                Outcome(
                    writeError == null,
                    Message(if (writeError == null) R.string.gpu_lock_released else R.string.gpu_release_failed),
                    detail = writeError,
                )
            }

            GpuChannel.GPUFREQ_V1 -> {
                val writeError = Sysfs.writeOrNull(GpuScanner.GPUFREQ_V1_OPP_FREQ, "0")
                Outcome(
                    writeError == null,
                    Message(if (writeError == null) R.string.gpu_lock_released else R.string.gpu_release_failed),
                    detail = writeError,
                )
            }

            GpuChannel.DEVFREQ -> {
                val root = GpuScanner.DEVFREQ_ROOT
                val (beforeMin, beforeMax) = devfreqBaseline ?: devfreqHardwareRange()
                val writeErrors = mutableListOf<String>()
                // Ceiling first, so the floor written next can never exceed it.
                beforeMax?.let {
                    Sysfs.writeOrNull("$root/max_freq", it.toString())?.let(writeErrors::add)
                }
                beforeMin?.let {
                    Sysfs.writeOrNull("$root/min_freq", it.toString())?.let(writeErrors::add)
                }
                val ok = writeErrors.isEmpty()
                if (ok) devfreqBaseline = null
                Outcome(
                    ok,
                    Message(if (ok) R.string.gpu_range_restored else R.string.gpu_restore_failed),
                    detail = writeErrors.joinToString("\n").ifBlank { null },
                )
            }

            GpuChannel.NONE -> Outcome(false, Message(R.string.gpu_no_interface))
        }
    }

    /**
     * Fallback for when no snapshot exists — the app restarted while a range was
     * still in force. The hardware range from `available_frequencies` is the
     * honest answer; reading back the live min/max would return our own lock and
     * call it a restore.
     */
    private suspend fun devfreqHardwareRange(): Pair<Long?, Long?> {
        val freqs = Sysfs.read("${GpuScanner.DEVFREQ_ROOT}/available_frequencies")
            ?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.sorted().orEmpty()
        return (freqs.firstOrNull() to freqs.lastOrNull())
    }
}
