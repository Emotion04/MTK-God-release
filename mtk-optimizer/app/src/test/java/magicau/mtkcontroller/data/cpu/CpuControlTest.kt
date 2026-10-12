package magicau.mtkcontroller.data.cpu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import magicau.mtkcontroller.data.log.AppLog
import magicau.mtkcontroller.data.powerhal.PerfHandleStorage
import magicau.mtkcontroller.data.powerhal.PerfLockController
import magicau.mtkcontroller.data.powerhal.PowerHal
import magicau.mtkcontroller.domain.model.ClusterSetting
import magicau.mtkcontroller.domain.model.CpuCluster
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CpuControlTest {
    private val cluster = CpuCluster("policy4", 1, availableFreqs = listOf(1100000, 1400000, 2400000, 2700000, 2850000))
    private val low = ClusterSetting("policy4", 1100000, 1400000)
    private val high = ClusterSetting("policy4", 2700000, 2850000)

    @Before fun quietLogcat() { AppLog.setMirrorToLogcat(false) }

    @Test fun `camera policy uses separate transport and releases on read failure`() = runBlocking {
        val memory = Memory()
        val calls = mutableListOf<Pair<Boolean, Int>>()
        var reads = 0
        val cpu = CpuControl(memory, PerfLockController(memory, "cpu", { _, _ -> error("不能写 MTK") }),
            { if (++reads == 4) error("读回失败"); nodes(550000, 2400000) }, {},
            vivoRequest = { error("不能写旧资源") },
            vivoPolicyRequest = { start, profile -> calls += start to profile; "ACCEPTED" })
        try {
            cpu.probe(listOf(cluster), cluster.policy, 0, 0, CpuProbeMode.VIVO_POLICY_71) {}
            fail("应保留读取失败")
        } catch (e: IllegalStateException) { assertEquals("读回失败", e.message) }
        assertEquals(listOf(true to 71, false to 71), calls)
        assertTrue(memory.saved.isEmpty())
    }

    private class Memory : CpuSettingsStore, PerfHandleStorage {
        var saved = emptyList<ClusterSetting>()
        var handle = 0
        override suspend fun baseline(): String? = null
        override suspend fun setBaseline(value: String?) = Unit
        override suspend fun lastApplied() = saved
        override suspend fun setLastApplied(settings: List<ClusterSetting>) { saved = settings }
        override suspend fun get(name: String) = handle
        override suspend fun set(name: String, value: Int) { handle = value }
    }

    private fun nodes(lo: Long?, hi: Long?, cur: Long? = lo) = mapOf(
        "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq" to lo?.toString(),
        "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq" to hi?.toString(),
        "/sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq" to cur?.toString(),
    )

    @Test fun `vivo probe owns no MTK handle and records before after release`() = runBlocking {
        val memory = Memory()
        val calls = mutableListOf<Boolean>()
        val log = mutableListOf<String>()
        val cpu = CpuControl(memory, PerfLockController(memory, "cpu", { _, _ -> error("不能发送 MTK 请求") }),
            { nodes(1100000, 2400000) }, {}, vivoRequest = { calls += it; if (it) "ACCEPTED" else "release return=1" })
        assertTrue(cpu.probe(listOf(cluster), cluster.policy, 0, 0, CpuProbeMode.VIVO_LIMIT, log::add).success)
        assertEquals(listOf(true, false), calls)
        assertEquals(11, log.count { "readMs=" in it })
        assertTrue(memory.saved.isEmpty())
        assertEquals(0, memory.handle)
    }

    @Test fun `vivo rejection is not success and still attempts cleanup`() = runBlocking {
        val memory = Memory()
        val calls = mutableListOf<Boolean>()
        val cpu = CpuControl(memory, PerfLockController(memory, "cpu"), { nodes(1100000, 2400000) }, {},
            vivoRequest = { calls += it; "ERROR SecurityException" })
        assertFalse(cpu.probe(listOf(cluster), cluster.policy, 0, 0, CpuProbeMode.VIVO_LIMIT) {}.success)
        assertEquals(listOf(true, false), calls)
    }

    @Test fun `vivo cancellation after acquire still releases`() = runBlocking {
        val memory = Memory()
        val acquired = CompletableDeferred<Unit>()
        val calls = mutableListOf<Boolean>()
        val cpu = CpuControl(memory, PerfLockController(memory, "cpu"), { nodes(1100000, 2400000) },
            { if (calls == listOf(true)) kotlinx.coroutines.awaitCancellation() },
            vivoRequest = { calls += it; if (it) acquired.complete(Unit); "ACCEPTED" })
        val job = launch { cpu.probe(listOf(cluster), cluster.policy, 0, 0, CpuProbeMode.VIVO_LIMIT) {} }
        acquired.await(); job.cancel(); job.join()
        assertEquals(listOf(true, false), calls)
    }

    @Test fun `vivo probe refuses existing CPU requests`() = runBlocking {
        val memory = Memory().also { it.saved = listOf(low) }
        val cpu = CpuControl(memory, PerfLockController(memory, "cpu"), { emptyMap() }, {},
            vivoRequest = { error("已有设置不得试写") })
        try {
            cpu.probe(listOf(cluster), cluster.policy, 0, 0, CpuProbeMode.VIVO_LIMIT) {}
            fail("应拒绝已有设置")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(low), memory.saved)
    }

    @Test fun `collapsed high range is not reported as verified`() = runBlocking {
        val memory = Memory()
        val lock = PerfLockController(memory, "cpu", { _, _ -> PowerHal.Result.Success(1, "ok") })
        val cpu = CpuControl(memory, lock, { nodes(2400000, 2400000) }, {})
        val result = cpu.apply(listOf(cluster), listOf(high))
        assertTrue(result.success) // The request is held and must remain releasable.
        assertEquals(CpuVerification.CONFLICT, cpu.status.value.verification)
        assertTrue(result.message.contains("实际上限低于请求下限"))
    }

    @Test fun `missing final sample cannot reuse earlier success`() = runBlocking {
        val memory = Memory()
        var reads = 0
        val cpu = CpuControl(memory, PerfLockController(memory, "cpu", { _, _ -> PowerHal.Result.Success(1, "ok") }),
            { if (++reads < 3) nodes(1100000, 1400000) else emptyMap() }, {})
        cpu.apply(listOf(cluster), listOf(low))
        assertEquals(CpuVerification.UNREADABLE, cpu.status.value.verification)
    }

    @Test fun `range normalization sends hard floor and point keeps four resources`() = runBlocking {
        // 2026-09-24 真机实测：硬上下限**能**做区间（policy7 上 800000-1600000 完全符合预期），
        // 只是超出系统包络（约 2000-2300）后退化成钉死。硬 MIN 是下限不是钉死 ——
        // 请求 300000 时下限被硬件钳到 600000（cpuinfo_min_freq），不是钉在 300000。
        // 所以区间要带硬下限，否则请求完全暴露在常驻上限之下。
        val memory = Memory()
        var sent = emptyList<Pair<String, String>>()
        val lock = PerfLockController(memory, "cpu", { pairs, _ -> sent = pairs; PowerHal.Result.Success(1, "ok") },
            { PowerHal.Result.Success(0, "ok") })
        val cpu = CpuControl(memory, lock, { nodes(1100000, 1400000) }, {})
        // 反向输入（min>max）要被归一化成 floor/ceiling
        cpu.apply(listOf(cluster), listOf(low.copy(minFreqKhz = 1400000, maxFreqKhz = 1100000)))
        assertEquals(listOf("0x00400100" to "1100000", "0x00404100" to "1400000",
            "0x00408100" to "1100000", "0x0040C100" to "1400000"), sent)
        assertEquals(listOf(low), memory.saved)
        // 定点请求同样是四项相等
        cpu.apply(listOf(cluster), listOf(low.copy(maxFreqKhz = 1100000)))
        assertEquals(4, sent.size)
        assertTrue(sent.all { it.second == "1100000" })
        assertTrue("定点请求要含硬下限", sent.any { it.first == "0x00408100" })
    }

    @Test fun `sched companions are appended verbatim without policy offset`() = runBlocking {
        // 配合项不是按簇资源，绝不能被加上 policyIndex * 0x100。
        // 加错了 opcode 会跳到别的资源上，而且不报错，只在日志里留一条没人理的 hint。
        val memory = Memory()
        var sent = emptyList<Pair<String, String>>()
        val lock = PerfLockController(memory, "cpu", { pairs, _ -> sent = pairs; PowerHal.Result.Success(1, "ok") },
            { PowerHal.Result.Success(0, "ok") })
        val companion = listOf(PowerHal.SCHED_CLOSE_PELT_ENABLE to "0", PowerHal.SCHED_UCLAMP_MIN_TA to "70")
        val cpu = CpuControl(
            memory, lock, { nodes(1100000, 1400000) },
            schedCompanions = { companion },
        )
        cpu.apply(listOf(cluster), listOf(low))
        // 频率四项 + 配合两项，且配合项 opcode 原样
        assertEquals(6, sent.size)
        assertTrue(companion.all { (opcode, value) ->
            sent.contains(PowerHal.fixedCommandId(opcode) to value)
        })
        assertTrue("频率项仍在最前", sent.first().first == "0x00400100")
    }

    @Test fun `no companion leaves request untouched`() = runBlocking {
        val memory = Memory()
        var sent = emptyList<Pair<String, String>>()
        val lock = PerfLockController(memory, "cpu", { pairs, _ -> sent = pairs; PowerHal.Result.Success(1, "ok") },
            { PowerHal.Result.Success(0, "ok") })
        // 不传 companion = 默认关，行为与改动前完全一致
        val cpu = CpuControl(memory, lock, { nodes(1100000, 1400000) }, {})
        cpu.apply(listOf(cluster), listOf(low))
        assertEquals(4, sent.size)
        assertTrue(sent.none { it.first.startsWith("0x1000c") || it.first.startsWith("0x0140") })
    }

    @Test fun `release waits for verification and queued polling cannot resurrect settings`() = runBlocking {
        val memory = Memory()
        val sampling = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var acquisitions = 0
        val lock = PerfLockController(memory, "cpu", { _, _ -> acquisitions++; PowerHal.Result.Success(acquisitions, "ok") },
            { PowerHal.Result.Success(0, "ok") })
        val cpu = CpuControl(memory, lock, { nodes(1100000, 1400000) }, {
            if (!sampling.isCompleted) { sampling.complete(Unit); resume.await() }
        })
        val applying = async { cpu.apply(listOf(cluster), listOf(low)) }
        sampling.await()
        val releasing = async { cpu.release(listOf(cluster)) }
        yield()
        val polling = async { cpu.reapply(listOf(cluster)) }
        yield()
        assertFalse(releasing.isCompleted)
        resume.complete(Unit)
        applying.await(); releasing.await(); polling.await()
        assertEquals(1, acquisitions)
        assertEquals(0, memory.handle)
        assertTrue(memory.saved.isEmpty())
        assertTrue(cpu.status.value.applied.isEmpty())
    }

    @Test fun `cancel after acquire still saves releasable handle and settings`() = runBlocking {
        val memory = Memory()
        val acquired = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val lock = PerfLockController(memory, "cpu", { _, _ -> acquired.complete(Unit); resume.await(); PowerHal.Result.Success(42, "ok") })
        val cpu = CpuControl(memory, lock, { nodes(1100000, 1400000) }, {})
        val applying = launch { cpu.apply(listOf(cluster), listOf(low)) }
        acquired.await()
        applying.cancel()
        resume.complete(Unit)
        applying.join()
        assertEquals(42, memory.handle)
        assertEquals(listOf(low), memory.saved)
        assertEquals(42, cpu.status.value.handler)
    }

    @Test fun `ceiling override is a conflict even when the floor matches`() = runBlocking {
        val memory = Memory()
        val cpu = CpuControl(memory, PerfLockController(memory, "cpu", { _, _ -> PowerHal.Result.Success(1, "ok") }),
            { nodes(1100000, 2400000, 2000000) }, {})
        cpu.apply(listOf(cluster), listOf(low))
        assertEquals(CpuVerification.CONFLICT, cpu.status.value.verification)
        assertTrue(cpu.status.value.verificationDetail!!.contains("读回超出请求范围"))
    }

    @Test fun `satisfied bounds are observed without reacquiring`() = runBlocking {
        val memory = Memory()
        var acquisitions = 0
        val cpu = CpuControl(memory, PerfLockController(memory, "cpu", { _, _ ->
            acquisitions++; PowerHal.Result.Success(acquisitions, "ok")
        }), { nodes(1100000, 1400000) }, {})
        cpu.apply(listOf(cluster), listOf(low))
        repeat(3) { cpu.reapply(listOf(cluster)) }
        assertEquals(1, acquisitions)
        assertEquals(CpuVerification.VERIFIED, cpu.status.value.verification)
    }

    @Test fun `failed release prevents acquire and retains settings and handle`() = runBlocking {
        val memory = Memory().apply { handle = 42; saved = listOf(low) }
        var acquisitions = 0
        val cpu = CpuControl(memory, PerfLockController(memory, "cpu", { _, _ ->
            acquisitions++; PowerHal.Result.Success(99, "ok")
        }, { PowerHal.Result.Failure("blocked") }), { nodes(1100000, 1400000) }, {})
        assertFalse(cpu.apply(listOf(cluster), listOf(high)).success)
        assertFalse(cpu.release(listOf(cluster)).success)
        assertEquals(0, acquisitions)
        assertEquals(42, memory.handle)
        assertEquals(listOf(low), memory.saved)
    }

    @Test fun `failed replacement after release stops old replay`() = runBlocking {
        val memory = Memory().apply { handle = 42; saved = listOf(low) }
        var acquisitions = 0
        val cpu = CpuControl(memory, PerfLockController(memory, "cpu", { _, _ ->
            acquisitions++; PowerHal.Result.Failure("acquire failed")
        }, { PowerHal.Result.Success(0, "ok") }), { nodes(1100000, 1400000) }, {})
        assertFalse(cpu.apply(listOf(cluster), listOf(high)).success)
        cpu.reapply(listOf(cluster))
        assertEquals(1, acquisitions)
        assertEquals(0, memory.handle)
        assertTrue(memory.saved.isEmpty())
        assertTrue(cpu.release(listOf(cluster)).success)
    }

    @Test fun `missing limits cannot hide observed frequency outside request`() {
        val reading = CpuLimitReading("policy4", null, null, 2400000)
        assertEquals(CpuVerification.CONFLICT, reading.verify(high))
        assertTrue(reading.conflictReason(high).contains("上下限读回不完整"))
    }

    @Test fun `crossing a lower effective ceiling is constrained rather than verified`() {
        val request = low.copy(maxFreqKhz = 2850000)
        val reading = CpuLimitReading("policy4", 1100000, 2400000, 1500000)
        assertEquals(CpuVerification.CONSTRAINED, reading.verify(request))
    }

    @Test fun `probe expires and restores original point settings`() = runBlocking {
        val original = low.copy(maxFreqKhz = low.minFreqKhz)
        val memory = Memory().apply { handle = 42; saved = listOf(original) }
        val durations = mutableListOf<Int>()
        val requests = mutableListOf<List<Pair<String, String>>>()
        val cpu = CpuControl(memory, PerfLockController(memory, "cpu", { pairs, duration ->
            requests += pairs; durations += duration; PowerHal.Result.Success(100 + durations.size, "ok")
        }, { PowerHal.Result.Success(0, "ok") }), { nodes(1100000, 1100000) }, {})
        assertTrue(cpu.probe(listOf(cluster), cluster.policy, 1100000, 1400000, CpuProbeMode.HARD_RANGE) {}.success)
        assertEquals(listOf(8000, 0), durations)
        assertEquals("1100000", requests.first().first { it.first == "0x00408100" }.second)
        assertEquals("1400000", requests.first().first { it.first == "0x0040C100" }.second)
        assertEquals(listOf(original), memory.saved)
    }

    @Test fun `load failure during sampling stops workers before releasing request`() = runBlocking {
        val memory = Memory()
        var running = false
        var releases = 0
        val cpu = CpuControl(memory, PerfLockController(memory, "cpu",
            { _, _ -> PowerHal.Result.Success(42, "ok") }, {
                assertFalse(running); releases++; PowerHal.Result.Success(0, "ok")
            }), { nodes(1100000, 1400000) }, {}, {
                running = true
                AutoCloseable { running = false }
            })
        try {
            cpu.probe(listOf(cluster), cluster.policy, 1100000, 1400000, CpuProbeMode.RANGE_LOAD) {
                if (it.startsWith("测试 1/8")) error("thermal stop")
            }
            org.junit.Assert.fail("should propagate sampling failure")
        } catch (e: IllegalStateException) { assertEquals("thermal stop", e.message) }
        assertFalse(running)
        assertEquals(1, releases)
        assertEquals(0, memory.handle)
        assertTrue(memory.saved.isEmpty())
    }

    @Test fun `loaded modes preserve corresponding frequency requests`() {
        assertTrue(CpuProbeMode.CAMERA.pairs(2, 2500000, 3100000).isEmpty())
        assertEquals(CpuProbeMode.RANGE.pairs(2, 2500000, 3100000), CpuProbeMode.RANGE_LOAD.pairs(2, 2500000, 3100000))
        assertEquals(CpuProbeMode.HARD_RANGE.pairs(2, 2500000, 3100000), CpuProbeMode.HARD_RANGE_LOAD.pairs(2, 2500000, 3100000))
        assertEquals(CpuProbeMode.POINT.pairs(2, 2500000, 3100000), CpuProbeMode.POINT_LOAD.pairs(2, 2500000, 3100000))
    }

    @Test fun `busy sampler handles guest idle resets and missing access`() {
        val sampler = CpuBusySampler()
        assertEquals("cpu0=?", sampler.sample("cpu0 10 0 0 10 0 0 0 0 8 0"))
        assertEquals("cpu0=50%", sampler.sample("cpu0 20 0 0 20 0 0 0 0 18 0"))
        assertEquals("cpu0=?", sampler.sample("cpu0 1 0 0 1 0 0 0 0"))
        assertTrue(sampler.sample(null).contains("不可读"))
        assertEquals("cpu0=?", sampler.sample("cpu0 30 0 0 30 0 0 0 0"))
    }

    @Test fun `read only probe does not release or acquire`() = runBlocking {
        val memory = Memory().apply { handle = 42; saved = listOf(low) }
        val cpu = CpuControl(memory, PerfLockController(memory, "cpu",
            { _, _ -> error("must not acquire") }, { error("must not release") }),
            { nodes(1100000, 1400000) }, {})
        cpu.probe(listOf(cluster), cluster.policy, 1100000, 1400000, CpuProbeMode.OBSERVE) {}
        assertEquals(42, memory.handle)
        assertEquals(listOf(low), memory.saved)
    }

    @Test fun `cancelling probe releases experiment and restores previous request`() = runBlocking {
        val memory = Memory().apply { handle = 42; saved = listOf(low) }
        val active = CompletableDeferred<Unit>()
        var acquisitions = 0
        var running = false
        val cpu = CpuControl(memory, PerfLockController(memory, "cpu", { _, _ ->
            acquisitions++; PowerHal.Result.Success(100 + acquisitions, "ok")
        }, { assertFalse(running); PowerHal.Result.Success(0, "ok") }), { nodes(1100000, 1400000) }, {
            if (acquisitions == 1 && !active.isCompleted) { active.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        }, { running = true; AutoCloseable { running = false } })
        val task = launch { cpu.probe(listOf(cluster), cluster.policy, 1100000, 1400000, CpuProbeMode.RANGE_LOAD) {} }
        active.await(); task.cancel(); task.join()
        assertEquals(2, acquisitions)
        assertEquals(listOf(low), memory.saved)
        assertFalse(running)
    }
}
