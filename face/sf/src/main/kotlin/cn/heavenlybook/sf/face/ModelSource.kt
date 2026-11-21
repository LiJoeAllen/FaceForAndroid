// ModelSource.kt —— assets 模型来源探测结果：目录 / 7z 压缩包 / 缺失。
// 安装逻辑据此决定「直接复制」还是「先解压」。

package cn.heavenlybook.sf.face

/** assets 中的模型来源。 */
sealed interface ModelSource {

    /** 模型来源相对 assets 根目录的路径，例如 `seeta` 或 `seeta.7z`。 */
    val assetPath: String

    /** 供日志和 UI 展示的中文描述。 */
    val displayName: String

    /** `assets/seeta/` 下直接就是 `.csta` 文件。 */
    data class Directory(
        override val assetPath: String,
        val files: List<String>,
    ) : ModelSource {
        override val displayName: String
            get() = "assets/$assetPath 目录（${files.size} 个文件）"
    }

    /** `assets/seeta.7z`（或 `assets/seeta/seeta.7z`）压缩包。 */
    data class Archive(
        override val assetPath: String,
        val sizeBytes: Long,
    ) : ModelSource {
        override val displayName: String
            get() = "assets/$assetPath 压缩包（${formatSize(sizeBytes)}）"
    }

    /** assets 中没有找到模型。 */
    data object Missing : ModelSource {
        override val assetPath: String = ""
        override val displayName: String = "assets 中未找到模型"
    }
}

/** 字节数转人类可读文本；日志/进度提示共用。 */
internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}