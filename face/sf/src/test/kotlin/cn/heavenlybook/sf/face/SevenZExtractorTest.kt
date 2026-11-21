// SevenZExtractorTest.kt —— 7z 解压与边界通道的 JVM 单测：
// 覆盖正常解压、进度回调、路径穿越拦截和 APK 内资源通道的偏移读取。

package cn.heavenlybook.sf.face

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class SevenZExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `解压 7z 并回调进度`() {
        val archive = temp.newFile("models.7z")
        val detector = ByteArray(4096) { (it % 251).toByte() }
        val recognizer = "fake-recognizer".toByteArray()
        createArchive(
            archive,
            "seeta/face_detector.csta" to detector,
            "seeta/face_recognizer.csta" to recognizer,
        )

        val target = temp.newFolder("extract")
        var lastDone = 0
        var lastTotal = 0
        var lastBytes = 0L
        var lastBytesTotal = 0L

        RandomAccessFile(archive, "r").channel.use { channel ->
            SevenZExtractor.extract(channel, target) { done, total, bytes, bytesTotal ->
                lastDone = done
                lastTotal = total
                lastBytes = bytes
                lastBytesTotal = bytesTotal
            }
        }

        assertArrayEquals(detector, File(target, "seeta/face_detector.csta").readBytes())
        assertArrayEquals(recognizer, File(target, "seeta/face_recognizer.csta").readBytes())
        assertEquals(2, lastDone)
        assertEquals(2, lastTotal)
        assertEquals((detector.size + recognizer.size).toLong(), lastBytes)
        assertEquals(lastBytes, lastBytesTotal)
    }

    @Test
    fun `从带前缀偏移的通道解压`() {
        val archive = temp.newFile("offset.7z")
        createArchive(archive, "seeta/face_detector.csta" to ByteArray(32) { it.toByte() })
        val archiveBytes = archive.readBytes()
        val prefix = ByteArray(37) { 0x7f }
        val container = temp.newFile("apk-container.bin")
        container.writeBytes(prefix + archiveBytes)
        val target = temp.newFolder("offset-extract")

        RandomAccessFile(container, "r").channel.use { channel ->
            BoundedSeekableByteChannel(
                channel,
                startOffset = prefix.size.toLong(),
                length = archiveBytes.size.toLong(),
            ).use { bounded ->
                SevenZExtractor.extract(bounded, target)
            }
        }

        assertArrayEquals(
            ByteArray(32) { it.toByte() },
            File(target, "seeta/face_detector.csta").readBytes(),
        )
    }

    @Test
    fun `拒绝路径穿越条目`() {
        val archive = temp.newFile("evil.7z")
        createArchive(archive, "../escape.txt" to "should-not-exist".toByteArray())

        val target = temp.newFolder("safe")
        val outside = File(temp.root, "escape.txt")

        RandomAccessFile(archive, "r").channel.use { channel ->
            assertThrows(IOException::class.java) {
                SevenZExtractor.extract(channel, target)
            }
        }

        assertFalse(outside.exists())
    }

    @Test
    fun `边界通道按偏移读取且不越界`() {
        val file = temp.newFile("apk.bin")
        file.writeBytes(ByteArray(16) { it.toByte() })

        RandomAccessFile(file, "r").channel.use { channel ->
            BoundedSeekableByteChannel(channel, startOffset = 4, length = 6).use { bounded ->
                assertEquals(6L, bounded.size())
                assertEquals(0L, bounded.position())

                val first = ByteBuffer.allocate(3)
                assertEquals(3, bounded.read(first))
                first.flip()
                assertEquals(4, first.get().toInt())
                assertEquals(5, first.get().toInt())
                assertEquals(6, first.get().toInt())

                bounded.position(5)
                val tail = ByteBuffer.allocate(8)
                assertEquals(1, bounded.read(tail))
                assertEquals(-1, bounded.read(tail))
                assertEquals(6L, bounded.position())
                assertTrue(bounded.isOpen)
            }
        }
    }

    private fun createArchive(archive: File, vararg entries: Pair<String, ByteArray>) {
        SevenZOutputFile(archive).use { output ->
            entries.forEach { (name, content) ->
                val entry = output.createArchiveEntry(File(name), name)
                output.putArchiveEntry(entry)
                output.write(content)
                output.closeArchiveEntry()
            }
        }
    }
}