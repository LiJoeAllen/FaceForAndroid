// ModelInstallProgress.kt —— 模型安装进度快照：把「检测来源 / 复制 / 解压 / 校验」
// 各阶段统一成 UI 可直接消费的数据，避免界面层关心具体安装方式。

package cn.heavenlybook.sf.face

/** 模型安装阶段；UI 可按阶段切换提示文案。 */
enum class ModelInstallStage {
    /** 正在探测 assets 里是目录还是 7z 压缩包。 */
    DETECTING,

    /** 压缩包在 assets 中被压缩存放，先复制到 cache 以便随机读取。 */
    PREPARING,

    /** 直接复制 assets 目录中的模型文件。 */
    COPYING,

    /** 解压 seeta.7z。 */
    EXTRACTING,

    /** 校验模型数量、大小并写入安装标记。 */
    VERIFYING,

    /** 模型已就绪。 */
    READY,
}

/**
 * 一次安装进度。
 *
 * @param entryIndex 已完成的条目数（文件数）。
 * @param entryCount 总条目数；未知时为 0。
 * @param bytesDone 已处理字节数；未知时为 0。
 * @param bytesTotal 总字节数；未知时为 0。
 */
data class ModelInstallProgress(
    val stage: ModelInstallStage,
    val message: String,
    val entryIndex: Int = 0,
    val entryCount: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
) {
    /** 0..1 的进度；总量未知时按条目数估算，仍未知则返回 null（UI 显示不确定进度）。 */
    val fraction: Float?
        get() = when {
            bytesTotal > 0 -> (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
            entryCount > 0 -> (entryIndex.toDouble() / entryCount).toFloat().coerceIn(0f, 1f)
            else -> null
        }
}