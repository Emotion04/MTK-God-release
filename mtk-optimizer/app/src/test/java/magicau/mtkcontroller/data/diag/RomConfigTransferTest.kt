package magicau.mtkcontroller.data.diag

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class RomConfigTransferTest {
    @Test fun largeBinaryRoundTripAndArchiveRetainEveryByte() {
        val original = ByteArray(6_000_123) { (it % 251).toByte() }
        val source = RomConfigTransfer { original }
        val fields = source.open(0).split('|')
        var chunks = 0
        val received = RomConfigTransfer.verified(fields[1].toInt(), fields[2]) {
            chunks++; source.chunk(fields[0], it)
        }
        assertTrue(chunks > 10); assertArrayEquals(original, received)
        val file = Files.createTempFile("rom-config-test", ".zip").toFile()
        try {
            RomConfigTransfer.archive(file, mapOf("powercontable.xml" to received), "OK ${fields[2]}")
            ZipFile(file).use { zip ->
                assertArrayEquals(original, zip.getInputStream(zip.getEntry("powercontable.xml")).readBytes())
                assertTrue(zip.getInputStream(zip.getEntry("manifest.txt")).reader().readText().contains(fields[2]))
            }
        } finally { file.delete() }
    }
    @Test fun corruptionAndShortReadCannotPassVerification() {
        val good = byteArrayOf(1, 2, 3)
        assertThrows(IllegalStateException::class.java) {
            RomConfigTransfer.verified(3, RomConfigTransfer.sha(good)) { byteArrayOf(1, 2, 4) }
        }
        assertThrows(IllegalStateException::class.java) {
            RomConfigTransfer.verified(3, RomConfigTransfer.sha(good)) { byteArrayOf() }
        }
    }
    @Test fun expiredSessionsAndNonWhitelistedIndicesAreRejected() {
        val source = RomConfigTransfer { byteArrayOf(1) }
        val token = source.open(0).substringBefore('|')
        source.open(1)
        assertThrows(IllegalStateException::class.java) { source.chunk(token, 0) }
        assertThrows(IllegalArgumentException::class.java) { source.open(3) }
        val next = source.open(0).substringBefore('|')
        source.close(next)
        assertThrows(IllegalStateException::class.java) { source.chunk(next, 0) }
    }
    @Test fun failedAndOversizedFilesCannotBecomeSuccess() {
        assertThrows(java.io.IOException::class.java) { RomConfigTransfer { throw java.io.IOException("denied") }.open(0) }
        assertThrows(IllegalStateException::class.java) { RomConfigTransfer { ByteArray(RomConfigTransfer.MAX_BYTES + 1) }.open(0) }
        assertThrows(IllegalStateException::class.java) { RomConfigTransfer { byteArrayOf() }.open(0) }
    }
}
