// SeetaFace.kt —— 模型安装工具：把 assets 里的 .csta 模型首次复制到内部存储，
// 供只能按文件路径加载模型的 SeetaFace6 引擎使用。

package cn.heavenlybook.sf.face

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * 模型安装工具集。
 *
 * SeetaFace6 引擎只能从文件路径加载模型，而随 app 发布的模型通常放在
 * `assets/`（.csta 文件体积太大，不适合作为常规构建产物）。[installModels]
 * 把它们一次性复制到应用内部存储，并返回可直接交给
 * [SeetaFacePipeline.Builder] 的目录。
 */
object SeetaFace {

    /** 内部存储中的模型安装目录。 */
    fun modelsDir(context: Context): File = File(context.filesDir, "seeta").apply { mkdirs() }

    /** 单个模型文件的绝对路径，如 `modelPath(context, "face_detector.csta")`。 */
    fun modelPath(context: Context, fileName: String): String =
        File(modelsDir(context), fileName).absolutePath

    /**
     * 把 `assets/[assetDir]` 里的所有文件复制到 [modelsDir]，同名且同长度的
     * 文件跳过（重复启动零拷贝）。
     *
     * @throws IOException assets 目录缺失或复制失败时抛出。
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun installModels(context: Context, assetDir: String = "seeta"): File {
        val target = modelsDir(context)
        val assets = context.assets.list(assetDir)
            ?: throw IOException(
                "未找到模型目录 assets/$assetDir，" +
                    "请下载官方 .csta 模型并放入该目录。"
            )
        if (assets.isEmpty()) {
            throw IOException("assets/$assetDir 为空，缺少官方 .csta 模型文件。")
        }
        var copied = 0
        for (name in assets) {
            val destination = File(target, name)
            if (destination.exists() && destination.length() > 0) continue
            context.assets.open("$assetDir/$name").use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            copied++
        }
        Log.i(TAG, "模型安装完成 dir=${target.path} 总数=${assets.size} 本次新复制=$copied")
        return target
    }

    private const val TAG = "SeetaFace"
}
