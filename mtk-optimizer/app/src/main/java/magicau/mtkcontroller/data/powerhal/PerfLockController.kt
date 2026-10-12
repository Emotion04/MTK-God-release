package magicau.mtkcontroller.data.powerhal

import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import magicau.mtkcontroller.R
import magicau.mtkcontroller.data.Message
import magicau.mtkcontroller.data.log.AppLog

/**
 * Owns exactly one PowerHAL handle, for one named purpose.
 *
 * PowerHAL requests do not replace each other — they accumulate, and
 * `libpowerhal` merges every live request (floor = max of floors, ceiling = min
 * of ceilings). A handle that is lost is a handle that can never be released,
 * and the only thing that clears it afterwards is the death of the process that
 * issued it. Every rule below exists because breaking it has already cost real
 * debugging time:
 *
 *  - release before acquire; abort the acquire if the release fails
 *  - never forget a handle whose release was not confirmed
 *  - serialise apply and release, so a background service and a screen cannot
 *    interleave into two live handles
 *
 * Each independent area of control (CPU frequency, uclamp, touch boost, …) gets
 * its own instance and its own stored handle, so touching one never re-sends
 * another.
 */
class PerfLockController(
    private val store: PerfHandleStorage,
    val name: String,
    private val acquireRequest: suspend (List<Pair<String, String>>, Int) -> PowerHal.Result = PowerHal::acquire,
    private val releaseRequest: suspend (Int) -> PowerHal.Result = PowerHal::release,
) {

    private companion object {
        const val TAG = "PerfLock"

        /**
         * Gap between a oneway release and the acquire that follows it.
         *
         * Oneway and synchronous binder transactions to the same target are not
         * guaranteed to be processed in submission order.
         */
        const val RELEASE_SETTLE_MS = 200L
    }

    data class Outcome(
        val success: Boolean,
        val message: Message,
        /** 原始错误，默认收起、可复制。界面用 `FailureNote` 展示。 */
        val detail: String? = null,
    )

    private val mutex = Mutex()

    suspend fun handler(): Int = store.get(name)

    /**
     * Acquire a handle covering [pairs], releasing whatever this controller
     * held before.
     *
     * @param durationMs `0` means never expires — correct for a limit the user
     *   is deliberately holding. **Use a short non-zero value for anything
     *   experimental**, so a handle that goes missing cannot leave a permanent
     *   request behind.
     */
    suspend fun apply(pairs: List<Pair<String, String>>, durationMs: Int = 0): Outcome = mutex.withLock {
        withContext(NonCancellable) { applyLocked(pairs, durationMs) }
    }

    private suspend fun applyLocked(pairs: List<Pair<String, String>>, durationMs: Int): Outcome {
        if (pairs.isEmpty()) return Outcome(false, Message(R.string.lock_no_resources))

        val previous = store.get(name)
        if (previous > 0) {
            val released = releaseRequest(previous)
            if (released is PowerHal.Result.Failure) {
                AppLog.w(TAG, "[$name] 释放旧请求失败,放弃本次下发: ${AppLog.text(released.message)}")
                return Outcome(
                    false,
                    Message(R.string.lock_release_previous_failed, listOf(released.message)),
                    detail = released.detail,
                )
            }
            store.set(name, 0)
            AppLog.d(TAG, "[$name] 已提交旧请求释放 handler=$previous")

            // Oneway release is queued. This gap reduces overlap but is not an acknowledgment.
            delay(RELEASE_SETTLE_MS)
        }

        // INFO 而不是 DEBUG：下发了哪些 (opcode, value) 是判断"为什么没生效"的唯一依据，
        // 而默认日志级别是 INFO，用 d 会被整体滤掉 —— 于是出了问题时日志里什么都没有。
        AppLog.i(
            TAG,
            "[$name] 下发: " + pairs.joinToString(", ") { "${it.first}=${it.second}" } +
                " (duration=${if (durationMs == 0) "无限" else "${durationMs}ms"})",
        )

        val result = acquireRequest(pairs, durationMs)
        if (result is PowerHal.Result.Failure) {
            AppLog.e(TAG, "[$name] 下发失败: ${AppLog.text(result.message)}")
            return Outcome(false, result.message, detail = result.detail)
        }

        val newHandler = (result as PowerHal.Result.Success).handler
        store.set(name, newHandler)
        AppLog.d(TAG, "[$name] 请求已接受 handler=$newHandler")
        return Outcome(true, Message(R.string.lock_accepted))
    }

    /**
     * Release the handle and report it honestly.
     *
     * The release transaction is oneway, so its return value says nothing about
     * whether the request actually went away. Callers that need certainty must
     * read the backing node back themselves.
     */
    suspend fun release(): Outcome = mutex.withLock {
        withContext(NonCancellable) { releaseLocked() }
    }

    private suspend fun releaseLocked(): Outcome {
        val current = store.get(name)
        if (current <= 0) {
            AppLog.w(TAG, "[$name] 释放:没有已记录的 handler")
            return Outcome(false, Message(R.string.release_nothing_pending))
        }

        val released = releaseRequest(current)
        AppLog.i(TAG, "[$name] 释放请求 handler=$current -> ${AppLog.text(released.message)}")
        if (released is PowerHal.Result.Failure) {
            return Outcome(false, released.message, detail = released.detail)
        }

        store.set(name, 0)
        return Outcome(true, Message(R.string.lock_released))
    }
}
