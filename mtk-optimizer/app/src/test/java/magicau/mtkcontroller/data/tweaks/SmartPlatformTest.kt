package magicau.mtkcontroller.data.tweaks

import org.junit.Assert.*
import org.junit.Test

class SmartPlatformTest {
    @Test fun overlappingAiControlsRequireRestoreInBothDirections() {
        assertEquals(VivoFeature.PRELOAD, VivoTweaks.conflictingFeature(VivoFeature.AI, setOf(VivoFeature.PRELOAD)))
        assertEquals(VivoFeature.AI, VivoTweaks.conflictingFeature(VivoFeature.PRELOAD, setOf(VivoFeature.AI)))
        assertNull(VivoTweaks.conflictingFeature(VivoFeature.AI, setOf(VivoFeature.AI, VivoFeature.MEMORY)))
    }
    @Test fun ignoresFailedUnknownAndMalformedReadings() {
        val result = SmartPlatform.parse("K|0|0|1\nK|1|1|Permission denied\nK|999|0|1\nS|0|0|Service aiengine_server: not found\nbroken\n[exit] 0")
        assertEquals("1", result["system ai_turbo_enabled"])
        assertFalse(result.containsKey("global ai_turbo_enabled"))
        assertEquals(2, result.size)
        assertTrue(result["aiengine_server"]!!.contains("not found"))
    }
    @Test fun doesNotTurnTimeoutOrMissingKeyIntoSupport() {
        assertTrue(SmartPlatform.parse("K|0|0|1\n[exit] 124").isEmpty())
        assertTrue(SmartPlatform.parse(null).isEmpty())
        assertEquals("null", SmartPlatform.parse("K|0|0|null\n[exit] 0")["system ai_turbo_enabled"])
        assertTrue(SmartPlatform.report(emptyMap()).contains("未读到"))
    }
    @Test fun serviceDumpKeepsMultilineErrorsAndMarksMissingExit() {
        val raw = "first\nPermission denied\n[dump-exit] 1\n[exit] 0"
        assertTrue(SmartPlatform.dumpSummary(raw).contains("Permission denied"))
        assertTrue(SmartPlatform.dumpSummary("partial\n[exit] 0").contains("不完整"))
        assertTrue(SmartPlatform.dumpSummary(null).contains("连接失败"))
    }
}
