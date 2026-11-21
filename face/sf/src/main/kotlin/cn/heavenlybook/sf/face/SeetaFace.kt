// SeetaFace.kt —— 模型安装工具：自动识别 assets 里的模型是「目录」还是
// 「seeta.7z 压缩包」，安装到内部存储后交给只能按文件路径加载的 SeetaFace6 引擎。
//
// 安装策略：
// - assets/seeta/ 直接是 .csta 文件：逐个复制，已存在且大小一致的文件跳过；
// - assets/seeta.7z（或 assets/seeta/seeta.7z）：流式读取并解压，先解到临时目录，
//   校验通过后再原子替换正式目录，避免进程被杀留下半套模型；
// - 内部存储已有完整模型而 assets 未打包模型时，直接复用旧安装。

package cn.heavenlybook.sf.face

import android.content.Context
import android.content.res.AssetManager
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 模型安装工具集。
 *
 * SeetaFace6 引擎只能从文件路径加载模型，而随 app 发布的模型通常放在
 * `assets/`。本工具把模型安装到应用内部存储，并返回可直接交给
 * [SeetaFacePipeline.Builder] 的目录。
 *
 * 所有耗时操作都在调用线程同步执行，调用方应放在 IO 线程；进度通过
 * [installModels] 的 `onProgress` 回调返回。
 */
object SeetaFace {

    /** 默认 assets 目录名；压缩包默认为 `assets/seeta.7z`。 */
    const val DEFAULT_ASSET_DIR = "seeta"

    /** 模型安装目录（应用私有）。 */
    fun modelsDir(context: Context): File = File(context.filesDir, DEFAULT_ASSET_DIR).apply { mkdirs() }

    /** 单个模型文件的绝对路径，如 `modelPath(context, "face_detector.csta")`。 */
    fun modelPath(context: Context, fileName: String): String =
        File(modelsDir(context), fileName).absolutePath

    /**
     * 探测 assets 中的模型来源，不执行安装。
     *
     * 优先级：`seeta/` 下直接有 `.csta` 文件 → `seeta.7z` → `seeta/seeta.7z`。
     * 目录非空但没有 `.csta` 时仍按目录返回，让安装阶段给出更具体的校验错误；
     * 目录不存在或为空则返回 [ModelSource.Missing]。
     */
    fun detectModelSource(context: Context, assetDir: String = DEFAULT_ASSET_DIR): ModelSource {
        val assets = context.assets
        val folderEntries = runCatching { assets.list(assetDir) }.getOrNull()
        val directModels = folderEntries
            ?.filter { it.endsWith(MODEL_SUFFIX, ignoreCase = true) }
            .orEmpty()
        if (directModels.isNotEmpty()) {
            return ModelSource.Directory(assetDir, directModels.sorted())
        }

        // 兼容两种放置方式：assets/seeta.7z 与 assets/seeta/seeta.7z。
        for (candidate in listOf("$assetDir$ARCHIVE_SUFFIX", "$assetDir/$assetDir$ARCHIVE_SUFFIX")) {
            val size = probeAssetSize(assets, candidate)
            if (size != null) {
                return ModelSource.Archive(candidate, size)
            }
        }

        if (!folderEntries.isNullOrEmpty()) {
            return ModelSource.Directory(assetDir, folderEntries.sorted())
        }
        return ModelSource.Missing
    }

    /**
     * 安装模型并返回内部存储中的模型目录。
     *
     * 已经安装且来源未变化时零拷贝返回；压缩包先解到临时目录，校验后再原子替换。
     *
     * @param onProgress 进度回调，在调用线程触发；可能被频繁调用，UI 层应做节流。
     * @throws IOException assets 中既没有目录也没有压缩包，或解压/校验失败时抛出。
     */
    @JvmOverloads
    @Synchronized
    @Throws(IOException::class)
    fun installModels(
        context: Context,
        assetDir: String = DEFAULT_ASSET_DIR,
        onProgress: ((ModelInstallProgress) -> Unit)? = null,
    ): File {
        val target = modelsDir(context)
        recoverInterruptedSwap(context, target)

        report(onProgress, ModelInstallStage.DETECTING, "检测模型来源…")
        val source = detectModelSource(context, assetDir)
        Log.i(TAG, "模型来源 type=${source::class.simpleName} path=${source.assetPath} 描述=${source.displayName}")

        if (source is ModelSource.Missing) {
            if (looksInstalled(target)) {
                Log.w(TAG, "assets 未发现模型，复用内部存储中的旧安装 dir=${target.path}")
                report(onProgress, ModelInstallStage.READY, "复用已安装模型", fractionDone = true)
                return target
            }
            throw IOException(
                "未找到模型：请在 assets/$assetDir/ 放置 .csta 文件，" +
                    "或放入 assets/$assetDir$ARCHIVE_SUFFIX 压缩包。",
            )
        }

        if (source is ModelSource.Archive && isArchiveUpToDate(target, source)) {
            Log.i(TAG, "压缩包未变化，跳过重复解压 path=${source.assetPath}")
            report(onProgress, ModelInstallStage.READY, "模型已就绪（跳过重复解压）", fractionDone = true)
            return target
        }

        when (source) {
            is ModelSource.Directory -> installFromDirectory(context, source, target, onProgress)
            is ModelSource.Archive -> installFromArchive(context, source, target, onProgress)
            ModelSource.Missing -> Unit // 前面已处理
        }

        report(onProgress, ModelInstallStage.VERIFYING, "校验模型文件…")
        verifyModels(target)
        report(onProgress, ModelInstallStage.READY, "模型已就绪", fractionDone = true)
        return target
    }

    /** 统计目录下的 `.csta` 文件数；日志/诊断用。 */
    fun countModels(dir: File): Int = cstaFiles(dir).size

    // ---------------------------------------------------------------------
    // 目录安装
    // ---------------------------------------------------------------------

    private fun installFromDirectory(
        context: Context,
        source: ModelSource.Directory,
        target: File,
        onProgress: ((ModelInstallProgress) -> Unit)?,
    ) {
        // 资产目录是开发期布局，可能被替换；先清掉旧的压缩包安装标记。
        File(target, MARKER_FILE).delete()
        target.mkdirs()
        val files = listAssetFiles(context.assets, source.assetPath)
        if (files.isEmpty()) {
            throw IOException("assets/${source.assetPath} 为空，缺少官方 .csta 模型文件。")
        }
        val totalBytes = files.sumOf { assetSize(context.assets, "${source.assetPath}/$it") }
        Log.i(
            TAG,
            "开始复制 assets 模型 目录=${source.assetPath} 文件数=${files.size} " +
                "总大小=${formatSize(totalBytes)}",
        )

        var doneBytes = 0L
        var lastReportAt = 0L
        files.forEachIndexed { index, relativePath ->
            val assetPath = "${source.assetPath}/$relativePath"
            val destination = File(target, relativePath)
            destination.parentFile?.mkdirs()
            val expectedSize = assetSize(context.assets, assetPath)
            if (destination.isFile && destination.length() > 0) {
                // 大小已知时严格比对；未知时沿用旧逻辑，避免重复复制。
                if (expectedSize <= 0 || destination.length() == expectedSize) {
                    doneBytes += destination.length()
                    report(
                        onProgress,
                        ModelInstallStage.COPYING,
                        "复用已存在的模型文件",
                        entryIndex = index + 1,
                        entryCount = files.size,
                        bytesDone = doneBytes,
                        bytesTotal = totalBytes,
                    )
                    return@forEachIndexed
                }
            }
            copyAsset(context.assets, assetPath, destination) { copied ->
                doneBytes += copied
                // 大文件按 120ms 节流，避免每 64KB 都触发一次 Compose 重组。
                val now = System.nanoTime()
                if (now - lastReportAt >= REPORT_INTERVAL_NS) {
                    lastReportAt = now
                    report(
                        onProgress,
                        ModelInstallStage.COPYING,
                        "复制模型文件 ${index + 1}/${files.size}",
                        entryIndex = index + 1,
                        entryCount = files.size,
                        bytesDone = doneBytes,
                        bytesTotal = totalBytes,
                    )
                }
            }
            report(
                onProgress,
                ModelInstallStage.COPYING,
                "复制模型文件 ${index + 1}/${files.size}",
                entryIndex = index + 1,
                entryCount = files.size,
                bytesDone = doneBytes,
                bytesTotal = totalBytes,
            )
        }
        Log.i(TAG, "assets 模型复制完成 dir=${target.path}")
    }

    /** 递归列出 assets 目录下的文件（相对路径）；最多下钻 4 层，避免异常目录结构。 */
    private fun listAssetFiles(
        assets: AssetManager,
        root: String,
        relative: String = "",
        depth: Int = 0,
    ): List<String> {
        val dir = if (relative.isEmpty()) root else "$root/$relative"
        val names = runCatching { assets.list(dir) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<String>()
        for (name in names) {
            val child = if (relative.isEmpty()) name else "$relative/$name"
            val childEntries = runCatching { assets.list("$root/$child") }.getOrNull()
            if (!childEntries.isNullOrEmpty() && depth < MAX_ASSET_DEPTH) {
                result += listAssetFiles(assets, root, child, depth + 1)
            } else {
                result += child
            }
        }
        return result
    }

    private fun copyAsset(
        assets: AssetManager,
        assetPath: String,
        destination: File,
        onChunk: (Long) -> Unit,
    ) {
        assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    onChunk(read.toLong())
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // 7z 解压安装
    // ---------------------------------------------------------------------

    private fun installFromArchive(
        context: Context,
        source: ModelSource.Archive,
        target: File,
        onProgress: ((ModelInstallProgress) -> Unit)?,
    ) {
        val staging = File(context.filesDir, STAGING_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            report(onProgress, ModelInstallStage.PREPARING, "准备读取 ${source.assetPath}…")
            val archiveSize = extractArchive(context, source, staging, onProgress)
            val modelRoot = locateModelDir(staging)
            val installRoot = if (modelRoot.canonicalFile == staging.canonicalFile) {
                staging
            } else {
                // 压缩包里多包了一层 seeta/，把这层目录单独挪出来。
                val normalized = File(context.filesDir, STAGING_FINAL_DIR).apply {
                    deleteRecursively()
                }
                if (!modelRoot.renameTo(normalized)) {
                    copyDirectory(modelRoot, normalized)
                }
                staging.deleteRecursively()
                normalized
            }

            report(onProgress, ModelInstallStage.VERIFYING, "校验解压出的模型…")
            verifyModels(installRoot)
            swapIn(installRoot, target, File(context.filesDir, BACKUP_DIR))
            val count = cstaFiles(target).size
            writeMarker(target, source, archiveSize, count)
            Log.i(
                TAG,
                "7z 模型安装完成 path=${source.assetPath} count=$count " +
                    "archive=${formatSize(archiveSize)} dir=${target.path}",
            )
        } catch (e: Exception) {
            staging.deleteRecursively()
            File(context.filesDir, STAGING_FINAL_DIR).deleteRecursively()
            Log.e(TAG, "7z 模型安装失败 path=${source.assetPath}", e)
            throw e
        }
    }

    /**
     * 从 assets 读取 7z 并解压到 [targetDir]。
     *
     * 优先用 `openFd` 直接拿到 APK 内未压缩资源的文件描述符，避免先复制几百 MB；
     * 资源被压缩存放时退回 cache 临时文件。返回压缩包字节数，供安装标记比对。
     */
    private fun extractArchive(
        context: Context,
        source: ModelSource.Archive,
        targetDir: File,
        onProgress: ((ModelInstallProgress) -> Unit)?,
    ): Long {
        val descriptor = runCatching { context.assets.openFd(source.assetPath) }.getOrNull()
        if (descriptor != null && descriptor.length > 0) {
            descriptor.use { afd ->
                Log.i(TAG, "直接解压 APK 内资源 path=${source.assetPath} size=${formatSize(afd.length)}")
                // dup 出独立的 fd，AssetFileDescriptor 关闭后解压流仍然可用。
                val pfd = afd.parcelFileDescriptor.dup()
                val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                BoundedSeekableByteChannel(input.channel, afd.startOffset, afd.length).use { channel ->
                    SevenZExtractor.extract(channel, targetDir) { done, total, bytes, bytesTotal ->
                        report(
                            onProgress,
                            ModelInstallStage.EXTRACTING,
                            "解压 ${source.assetPath}",
                            entryIndex = done,
                            entryCount = total,
                            bytesDone = bytes,
                            bytesTotal = bytesTotal,
                        )
                    }
                }
                return afd.length
            }
        }

        // 兼容被 aapt 压缩的 7z：先复制到 cache，再随机读取。
        val cache = File(context.cacheDir, CACHE_ARCHIVE)
        try {
            Log.w(TAG, "openFd 不可用，先复制压缩包到 cache path=${source.assetPath}")
            val copied = copyAssetToFile(context, source.assetPath, cache, onProgress)
            RandomAccessFile(cache, "r").channel.use { channel ->
                SevenZExtractor.extract(channel, targetDir) { done, total, bytes, bytesTotal ->
                    report(
                        onProgress,
                        ModelInstallStage.EXTRACTING,
                        "解压 ${source.assetPath}",
                        entryIndex = done,
                        entryCount = total,
                        bytesDone = bytes,
                        bytesTotal = bytesTotal,
                    )
                }
            }
            return copied
        } finally {
            cache.delete()
        }
    }

    private fun copyAssetToFile(
        context: Context,
        assetPath: String,
        destination: File,
        onProgress: ((ModelInstallProgress) -> Unit)?,
    ): Long {
        var copied = 0L
        var lastReportAt = 0L
        context.assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    copied += read
                    val now = System.nanoTime()
                    if (now - lastReportAt >= REPORT_INTERVAL_NS) {
                        lastReportAt = now
                        report(
                            onProgress,
                            ModelInstallStage.PREPARING,
                            "复制压缩包到临时目录",
                            bytesDone = copied,
                            bytesTotal = copied + input.available(),
                        )
                    }
                }
            }
        }
        return copied
    }

    /** 找到解压目录中真正存放 `.csta` 的那一层；支持压缩包内多包一层 seeta/。 */
    private fun locateModelDir(root: File): File {
        if (cstaFiles(root).isNotEmpty()) return root
        val candidates = root.walkTopDown()
            .maxDepth(MAX_MODEL_DIR_DEPTH)
            .filter { it.isDirectory && it != root && cstaFiles(it).isNotEmpty() }
            .sortedBy { it.relativeTo(root).path.count { ch -> ch == File.separatorChar } }
            .toList()
        return candidates.firstOrNull()
            ?: throw IOException("压缩包内未找到 .csta 模型文件：${root.path}")
    }

    // ---------------------------------------------------------------------
    // 原子替换 / 标记 / 校验
    // ---------------------------------------------------------------------

    /**
     * 用 [staging] 原子替换 [target]：先把旧目录改名为 backup，再把 staging
     * 改名为 target；任一步失败都尽量恢复旧目录。
     */
    private fun swapIn(staging: File, target: File, backup: File) {
        backup.deleteRecursively()
        if (target.exists()) {
            if (!target.renameTo(backup)) {
                throw IOException("无法备份旧模型目录：${target.path}")
            }
        }
        if (!staging.renameTo(target)) {
            // 回滚，保证旧模型仍可用。
            if (backup.exists()) backup.renameTo(target)
            throw IOException("无法安装模型目录：${staging.path} -> ${target.path}")
        }
        backup.deleteRecursively()
    }

    /** 上次安装若在「旧目录已备份、新目录尚未就位」时被杀，启动时恢复旧目录。 */
    private fun recoverInterruptedSwap(context: Context, target: File) {
        val backup = File(context.filesDir, BACKUP_DIR)
        val targetHasContent = target.isDirectory && target.list()?.isNotEmpty() == true
        if (!targetHasContent && backup.exists()) {
            Log.w(TAG, "检测到上次安装中断，恢复旧模型目录 backup=${backup.path}")
            target.deleteRecursively()
            if (!backup.renameTo(target)) {
                Log.e(TAG, "恢复旧模型目录失败 backup=${backup.path}")
            }
        } else if (targetHasContent && backup.exists()) {
            backup.deleteRecursively()
        }
        File(context.filesDir, STAGING_DIR).deleteRecursively()
        File(context.filesDir, STAGING_FINAL_DIR).deleteRecursively()
    }

    private fun writeMarker(target: File, source: ModelSource.Archive, size: Long, count: Int) {
        val text = buildString {
            appendLine("version=$MARKER_VERSION")
            appendLine("type=archive")
            appendLine("path=${source.assetPath}")
            appendLine("size=$size")
            appendLine("count=$count")
            appendLine("installedAt=${System.currentTimeMillis()}")
        }
        File(target, MARKER_FILE).writeText(text, Charsets.UTF_8)
    }

    private fun isArchiveUpToDate(target: File, source: ModelSource.Archive): Boolean {
        val marker = readMarker(target)
        if (marker["type"] != "archive") return false
        if (marker["path"] != source.assetPath) return false
        if (source.sizeBytes > 0 && marker["size"] != source.sizeBytes.toString()) return false
        val expectedCount = marker["count"]?.toIntOrNull() ?: return false
        val files = cstaFiles(target)
        return files.size == expectedCount && files.all { it.length() > 0 }
    }

    private fun readMarker(target: File): Map<String, String> {
        val marker = File(target, MARKER_FILE)
        if (!marker.isFile) return emptyMap()
        return runCatching {
            marker.readLines(Charsets.UTF_8)
                .mapNotNull { line ->
                    val index = line.indexOf('=')
                    if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
                }
                .toMap()
        }.getOrElse {
            Log.w(TAG, "读取安装标记失败 path=${marker.path}", it)
            emptyMap()
        }
    }

    private fun verifyModels(dir: File) {
        val files = cstaFiles(dir)
        if (files.isEmpty()) {
            throw IOException("模型目录中没有 .csta 文件：${dir.path}")
        }
        val empty = files.filter { it.length() <= 0 }
        if (empty.isNotEmpty()) {
            throw IOException("模型文件为空：${empty.joinToString { it.name }}")
        }
        Log.i(TAG, "模型校验通过 count=${files.size} 总大小=${formatSize(files.sumOf { it.length() })}")
    }

    /** 内部存储中已有可用模型（用于 assets 未打包模型时复用旧安装）。 */
    private fun looksInstalled(dir: File): Boolean {
        val files = cstaFiles(dir)
        return files.isNotEmpty() && files.all { it.length() > 0 }
    }

    private fun cstaFiles(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(MODEL_SUFFIX, ignoreCase = true) }
            .orEmpty()

    // ---------------------------------------------------------------------
    // assets 工具
    // ---------------------------------------------------------------------

    private fun assetSize(assets: AssetManager, path: String): Long =
        probeAssetSize(assets, path) ?: 0L

    /**
     * 返回 asset 的原始字节数；不存在返回 null。
     * 未压缩资源走 openFd（精确且不读内容），压缩资源退回 available()。
     */
    private fun probeAssetSize(assets: AssetManager, path: String): Long? {
        val descriptor = runCatching { assets.openFd(path) }.getOrNull()
        if (descriptor != null) {
            return descriptor.use { it.length }
        }
        return runCatching { assets.open(path).use { it.available().toLong() } }.getOrNull()
    }

    private fun copyDirectory(source: File, destination: File) {
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source).path
            val target = File(destination, relative)
            if (file.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
            }
        }
    }

    private fun report(
        callback: ((ModelInstallProgress) -> Unit)?,
        stage: ModelInstallStage,
        message: String,
        entryIndex: Int = 0,
        entryCount: Int = 0,
        bytesDone: Long = 0,
        bytesTotal: Long = 0,
        fractionDone: Boolean = false,
    ) {
        callback?.invoke(
            ModelInstallProgress(
                stage = stage,
                message = message,
                entryIndex = if (fractionDone) 1 else entryIndex,
                entryCount = if (fractionDone) 1 else entryCount,
                bytesDone = if (fractionDone) 1 else bytesDone,
                bytesTotal = if (fractionDone) 1 else bytesTotal,
            ),
        )
    }

    private const val TAG = "SeetaFace"
    private const val MODEL_SUFFIX = ".csta"
    private const val ARCHIVE_SUFFIX = ".7z"
    private const val MARKER_FILE = ".seeta-install"
    private const val MARKER_VERSION = "1"
    private const val STAGING_DIR = "seeta-installing"
    private const val STAGING_FINAL_DIR = "seeta-installing-final"
    private const val BACKUP_DIR = "seeta-backup"
    private const val CACHE_ARCHIVE = "seeta-asset.7z"
    private const val BUFFER_SIZE = 64 * 1024
    private const val REPORT_INTERVAL_NS = 120_000_000L
    private const val MAX_ASSET_DEPTH = 4
    private const val MAX_MODEL_DIR_DEPTH = 3
}