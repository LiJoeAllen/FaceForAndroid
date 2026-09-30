// FaceGalleryStore.kt —— 产品层人脸相册：在只存整数索引特征向量的原生数据库
// 之上，为每个索引补一张裁剪人像图和一个可编辑显示名，全部持久化到 filesDir。

package cn.heavenlybook.facemirror

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * 叠在原生 [cn.heavenlybook.sf.face.SeetaFaceDatabase] 之上的产品层注册表：
 * 原生库只按整数索引存特征向量，这里为每个索引补一张裁剪人像和一个可编辑
 * 显示名，二者都持久化在 `filesDir/gallery/<识别模型>/` 下 —— 特征只在同一个
 * 识别模型内可比，所以每个模型独立归档，切换模型不会销毁已注册数据。
 *
 * 原生 `Delete` 只删除映射键，存留索引不会移位 —— 全部以数据库索引为键是
 * 安全的。所有方法都是同步的，调用方需在工作线程执行。
 */
internal class FaceGalleryStore(filesDir: File, recognizerModel: String) {

    data class Entry(val index: Long, val name: String, val photo: File?)

    private val dir = File(File(filesDir, "gallery"), modelKey(recognizerModel)).apply { mkdirs() }
    private val registryFile = File(dir, "registry.json")
    private val names = LinkedHashMap<Long, String>()

    private fun modelKey(model: String) = model.removeSuffix(".csta")

    init {
        // 注册表损坏不应拖垮整个引擎，按空相册继续，靠日志暴露问题。
        runCatching {
            val obj = JSONObject(registryFile.readText())
            for (key in obj.keys()) names[key.toLong()] = obj.getString(key)
        }.onFailure {
            Log.w(TAG, "注册表 ${registryFile.name} 解析失败，按空相册继续", it)
        }
        Log.d(TAG, "人脸相册就绪 dir=${dir.path} 已登记 ${names.size} 条")
    }

    /** 人像图与注册表同目录，文件名即索引 `<index>.jpg`。 */
    fun photo(index: Long): File = File(dir, "$index.jpg")

    /**
     * 从 [source] 裁出 [rect] 区域（带外扩边距），压缩后存为 [index] 的人像图。
     * 限制最长边 320px：仅用于列表展示，避免原图尺寸的磁盘占用。
     */
    fun savePortrait(index: Long, source: Bitmap, rect: Rect) {
        val crop = cropFaceBitmap(source, rect, maxDim = 320)
        FileOutputStream(photo(index)).use { out ->
            crop.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        Log.d(TAG, "人像图已保存 index=$index 尺寸=${crop.width}x${crop.height}")
    }

    /** [index] 的显示名；未设置时回退为 `人脸 #index`。 */
    fun name(index: Long): String =
        names[index] ?: "人脸 #$index"

    fun setName(index: Long, name: String) {
        // 空白名字视为“删除自定义名”，回退到默认命名。
        if (name.isBlank()) names.remove(index) else names[index] = name.trim()
        persist()
    }

    fun entry(index: Long): Entry =
        Entry(index, name(index), photo(index).takeIf { it.isFile })

    /**
     * 注册表里命名的条目 + 目录里已有头像的条目(如注册后未改名的历史数据)
     * 的并集,按 index 升序 —— 只看 names 会漏掉没改过名的注册。
     */
    fun entries(): List<Entry> =
        (names.keys + photoIndexes()).toSortedSet().map { entry(it) }

    private fun photoIndexes(): Set<Long> =
        dir.listFiles()
            ?.mapNotNull { it.name.substringBeforeLast('.').toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    /** 删除 [index] 的人像图和显示名（原生库条目由调用方负责删除）。 */
    fun remove(index: Long) {
        names.remove(index)
        photo(index).delete()
        persist()
        Log.d(TAG, "相册条目已移除 index=$index")
    }

    fun clear() {
        names.clear()
        dir.listFiles()?.forEach { if (it.name != registryFile.name) it.delete() }
        persist()
        Log.i(TAG, "人脸相册已清空 dir=${dir.path}")
    }

    /** 注册表全量覆写；失败只告警不抛出 —— 名字属可重建数据，不值得让调用方崩溃。 */
    private fun persist() {
        runCatching {
            registryFile.writeText(JSONObject(names).toString())
        }.onFailure {
            Log.w(TAG, "注册表写入失败（内存态已更新，重启后丢失）entries=${names.size}", it)
        }
    }

    private companion object {
        const val TAG = "FaceGalleryStore"
    }
}
