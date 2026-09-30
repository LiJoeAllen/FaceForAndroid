// SeetaImages.kt —— 位图转换：Bitmap -> SeetaFace6 引擎所需的 BGR 像素布局。

package cn.heavenlybook.sf.face

import android.graphics.Bitmap
import com.seeta.sdk.SeetaImageData

/**
 * 逐帧转换缓冲：复用中间 [IntArray] 与 [SeetaImageData] 的像素数组。相机
 * 分析线程应长期持有一个实例 —— 每帧新分配约 1.2 MB 中间数组会高频触发
 * GC，直接拉长帧耗时。可放心覆写：native 侧每次 JNI 调用都会把像素整块
 * 拷走（`GetByteArrayRegion`），不在调用之间持有 Java 数组指针。
 *
 * 非线程安全：只归创建它的分析线程使用。
 */
class SeetaFrameBuffer {
    private var pixels = IntArray(0)
    private var image = SeetaImageData(0, 0, 3)

    /** 把 [source] 转成 BGR [SeetaImageData]；尺寸不变时零分配。 */
    fun convert(source: Bitmap): SeetaImageData {
        val width = source.width
        val height = source.height
        if (image.width != width || image.height != height) {
            image = SeetaImageData(width, height, 3)
            pixels = IntArray(width * height)
        }
        val rgba = if (source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, false)
        rgba.getPixels(pixels, 0, width, 0, 0, width, height)
        val data = image.data
        var index = 0
        for (pixel in pixels) {
            // ARGB 整型 -> BGR 字节序（SeetaFace 要求的通道顺序）。
            data[index++] = (pixel and 0xFF).toByte()          // B
            data[index++] = (pixel shr 8 and 0xFF).toByte()    // G
            data[index++] = (pixel shr 16 and 0xFF).toByte()   // R
        }
        return image
    }
}

/**
 * 把 [Bitmap] 转成 3 通道 BGR 的 [SeetaImageData]，即 SeetaFace6 引擎要求的
 * 像素布局。非 ARGB_8888 的位图会先复制一份再转换；相机流等热路径请改用
 * [SeetaFrameBuffer] 复用缓冲。
 */
fun Bitmap.toSeetaImageData(): SeetaImageData {
    val source = if (config == Bitmap.Config.ARGB_8888) this
    else copy(Bitmap.Config.ARGB_8888, false)

    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    val image = SeetaImageData(width, height, 3)
    val data = image.data
    var index = 0
    for (pixel in pixels) {
        // ARGB 整型 -> BGR 字节序（SeetaFace 要求的通道顺序）。
        data[index++] = (pixel and 0xFF).toByte()          // B
        data[index++] = (pixel shr 8 and 0xFF).toByte()    // G
        data[index++] = (pixel shr 16 and 0xFF).toByte()   // R
    }
    return image
}
