// SevenZExtractor.kt —— 纯 JVM 的 7z 解压实现（commons-compress + xz）。
//
// 只依赖 java.nio.channels.SeekableByteChannel（API 24 起可用），刻意不使用
// SevenZFile(File) 构造器：它内部走 java.nio.file（API 26），在 minSdk 24 上会崩。
// 解压前对每个条目做 canonical path 校验，拦截 ../ 或绝对路径的压缩包炸弹。

package cn.heavenlybook.sf.face

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/** 7z 解压器；不依赖 Android API，便于在 JVM 单测中覆盖。 */
internal object SevenZExtractor {

    /** 解压结果：文件条目数与解压后的字节数。 */
    data class Result(val entryCount: Int, val bytes: Long)

    /**
     * 把 [channel] 指向的 7z 解压到 [targetDir]。
     *
     * @param onProgress 参数依次为：已完成条目数、总条目数、已解压字节、总字节；
     *   总字节可能为 0（7z 头未记录大小时），此时调用方应按条目数显示进度。
     * @throws IOException 压缩包损坏、条目路径越界或写文件失败时抛出。
     */
    fun extract(
        channel: SeekableByteChannel,
        targetDir: File,
        onProgress: (doneEntries: Int, totalEntries: Int, doneBytes: Long, totalBytes: Long) -> Unit =
            { _, _, _, _ -> },
    ): Result {
        targetDir.mkdirs()
        SevenZFile(channel).use { archive ->
            val fileEntries = archive.getEntries().toList()
                .filter { it.hasStream() && !it.isDirectory && !it.isAntiItem }
            val totalEntries = fileEntries.size
            val totalBytes = fileEntries.sumOf { it.size.coerceAtLeast(0L) }
            var doneEntries = 0
            var doneBytes = 0L
            var lastReportAt = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            var entry = archive.nextEntry
            while (entry != null) {
                if (entry.isAntiItem) {
                    entry = archive.nextEntry
                    continue
                }
                val destination = safeResolve(targetDir, entry.name)
                if (entry.isDirectory) {
                    if (!destination.isDirectory && !destination.mkdirs()) {
                        throw IOException("创建模型子目录失败：${destination.path}")
                    }
                } else if (entry.hasStream()) {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().buffered(BUFFER_SIZE).use { output ->
                        while (true) {
                            val read = archive.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            doneBytes += read
                            val now = System.nanoTime()
                            if (now - lastReportAt >= REPORT_INTERVAL_NS) {
                                lastReportAt = now
                                onProgress(doneEntries, totalEntries, doneBytes, totalBytes)
                            }
                        }
                    }
                    doneEntries++
                    onProgress(doneEntries, totalEntries, doneBytes, totalBytes)
                }
                entry = archive.nextEntry
            }
            onProgress(doneEntries, totalEntries, doneBytes, totalBytes)
            return Result(doneEntries, doneBytes)
        }
    }

    /** 拒绝绝对路径、盘符和 `..` 越界条目，返回值一定是 [root] 之下的 canonical 路径。 */
    private fun safeResolve(root: File, name: String): File {
        val normalized = name.replace('\\', '/')
        if (normalized.isBlank()) {
            throw IOException("压缩包包含空文件名")
        }
        if (normalized.startsWith("/") || DRIVE_PREFIX.containsMatchIn(normalized)) {
            throw IOException("压缩包包含绝对路径，已拒绝：$name")
        }
        val rootCanonical = root.canonicalFile
        val destination = File(rootCanonical, normalized).canonicalFile
        val rootPrefix = rootCanonical.path + File.separator
        if (destination != rootCanonical && !destination.path.startsWith(rootPrefix)) {
            throw IOException("压缩包条目越界，已拒绝：$name")
        }
        return destination
    }

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    private const val BUFFER_SIZE = 64 * 1024
    private const val REPORT_INTERVAL_NS = 120_000_000L
}

/**
 * 把 APK assets 中的一段字节包装成可随机读取的通道。
 *
 * `AssetFileDescriptor` 给出的 fd 指向整个 APK，需用 [startOffset]/[length]
 * 限定窗口；这样解压 7z 时不需要先把几百 MB 的包复制到 cache。
 */
internal class BoundedSeekableByteChannel(
    private val channel: FileChannel,
    private val startOffset: Long,
    private val length: Long,
) : SeekableByteChannel {

    private var currentPosition = 0L
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        checkOpen()
        if (currentPosition >= length) return -1
        val remaining = minOf(dst.remaining().toLong(), length - currentPosition).toInt()
        if (remaining <= 0) return 0
        val oldLimit = dst.limit()
        dst.limit(dst.position() + remaining)
        val read = try {
            channel.read(dst, startOffset + currentPosition)
        } finally {
            dst.limit(oldLimit)
        }
        if (read > 0) currentPosition += read
        return read
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

    override fun position(): Long = currentPosition

    override fun position(newPosition: Long): SeekableByteChannel {
        checkOpen()
        require(newPosition in 0..length) { "position 超出范围：$newPosition（0..$length）" }
        currentPosition = newPosition
        return this
    }

    override fun size(): Long = length

    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()

    override fun isOpen(): Boolean = open

    override fun close() {
        if (open) {
            open = false
            channel.close()
        }
    }

    private fun checkOpen() {
        if (!open) throw ClosedChannelException()
    }
}