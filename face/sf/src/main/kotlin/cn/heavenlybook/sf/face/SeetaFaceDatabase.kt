// SeetaFaceDatabase.kt —— 1:N 人脸库门面：注册/检索/1:1 比对/落盘，内置
// 检测器与关键点器，可直接吃 Bitmap；上游 FaceDatabase 内部自带串行化。

package cn.heavenlybook.sf.face

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.seeta.sdk.FaceDatabase
import com.seeta.sdk.FaceDetector
import com.seeta.sdk.FaceLandmarker
import com.seeta.sdk.SeetaDevice
import com.seeta.sdk.SeetaModelSetting
import com.seeta.sdk.SeetaPointF
import com.seeta.sdk.SeetaRect
import java.io.File

/** 一次 1:N 检索的命中：库内索引 + 相似度。 */
data class Match(val index: Long, val similarity: Float)

/**
 * 1:N 人脸库（包装上游 `FaceDatabase`）：从 Bitmap 提取特征注册入库，之后
 * 可对新脸做全库检索，或用 [save]/[load] 把库持久化到磁盘。
 *
 * 特征只在同一个识别模型内可比 —— 更换 `withRecognizer(...)` 后必须重建
 * 人脸库。与其他 SDK 对象不同，上游文档说明 `FaceDatabase` 的调用可跨线程
 * 共享（内部自行串行化，而非并行执行）。
 */
class SeetaFaceDatabase private constructor(
    private val detector: FaceDetector,
    private val landmarker: FaceLandmarker,
    private val database: FaceDatabase,
) : AutoCloseable {

    class Builder(private val modelsDir: File) {

        private var device: SeetaDevice = SeetaDevice.SEETA_DEVICE_AUTO
        private var deviceId: Int = 0
        private var minFaceSize: Int = 40
        private var threshold: Double = 0.9
        private var maxImageWidth: Int = 0
        private var maxImageHeight: Int = 0
        private var landmarkerModel: String = "face_landmarker_pts5.csta"
        private var recognizerModel: String = "face_recognizer.csta"
        private var extractionThreads: Int = 0
        private var comparationThreads: Int = 0

        fun device(device: SeetaDevice, id: Int = 0): Builder = apply {
            this.device = device
            this.deviceId = id
        }

        fun withDetector(
            minFaceSize: Int = 40,
            threshold: Double = 0.9,
            maxImageWidth: Int = 0,
            maxImageHeight: Int = 0,
        ): Builder = apply {
            this.minFaceSize = minFaceSize
            this.threshold = threshold
            this.maxImageWidth = maxImageWidth
            this.maxImageHeight = maxImageHeight
        }

        fun withLandmarker(model: String = "face_landmarker_pts5.csta"): Builder = apply {
            landmarkerModel = model
        }

        /** 同一个人脸库内的所有特征必须来自同一个识别模型。 */
        fun withRecognizer(model: String = "face_recognizer.csta"): Builder = apply {
            recognizerModel = model
        }

        /** 内部工作线程数；0 保持上游默认值。 */
        fun withThreads(extraction: Int, comparation: Int): Builder = apply {
            extractionThreads = extraction
            comparationThreads = comparation
        }

        fun build(): SeetaFaceDatabase {
            fun models(vararg names: String) = SeetaModelSetting(
                deviceId, names.map { File(modelsDir, it).absolutePath }.toTypedArray(), device
            )
            val detector = FaceDetector(models("face_detector.csta")).apply {
                set(FaceDetector.Property.PROPERTY_MIN_FACE_SIZE, minFaceSize.toDouble())
                set(FaceDetector.Property.PROPERTY_THRESHOLD, threshold)
                if (maxImageWidth > 0) {
                    set(FaceDetector.Property.PROPERTY_MAX_IMAGE_WIDTH, maxImageWidth.toDouble())
                }
                if (maxImageHeight > 0) {
                    set(FaceDetector.Property.PROPERTY_MAX_IMAGE_HEIGHT, maxImageHeight.toDouble())
                }
            }
            val landmarker = FaceLandmarker(models(landmarkerModel))
            val database = if (extractionThreads > 0 || comparationThreads > 0) {
                FaceDatabase(models(recognizerModel), extractionThreads, comparationThreads)
            } else {
                FaceDatabase(models(recognizerModel))
            }
            Log.i(
                TAG,
                "人脸库构建完成 recognizer=$recognizerModel " +
                    "threads=($extractionThreads,$comparationThreads)",
            )
            return SeetaFaceDatabase(detector, landmarker, database)
        }
    }

    /** 取 [source] 中最大的人脸并配好关键点；没有人脸时返回 null。 */
    private fun largestNative(source: Bitmap): Pair<com.seeta.sdk.SeetaImageData, Array<SeetaPointF>>? {
        val image = source.toSeetaImageData()
        val rect: SeetaRect = detector.Detect(image)
            ?.maxByOrNull { it.width } ?: return null
        val points = Array(landmarker.number()) { SeetaPointF() }
        landmarker.mark(image, rect, points)
        return image to points
    }

    /**
     * 提取 [source] 中最大的人脸并注册。以此方式入库的特征只能与同一识别
     * 模型产出的特征比对。
     *
     * @return 新条目的库内索引；未检到人脸时返回 null。
     */
    fun add(source: Bitmap): Long? =
        largestNative(source)?.let { (image, points) -> database.Register(image, points) }

    /**
     * 注册一个已定位的人脸（如相机流的跟踪结果），跳过重复的检测步骤。
     */
    fun add(source: Bitmap, face: Rect, landmarks: List<PointF>): Long? {
        val (image, points) = nativeFace(source, face, landmarks)
        return database.Register(image, points)
    }

    /**
     * 用 [source] 的最大人脸对全库检索，无论得分高低返回最佳命中。「是否
     * 同一人」请拿 [Match.similarity] 与业务阈值比较（官方 0.62 是
     * `face_recognizer.csta` 的 1:1 参考值；1:N 通常应取更高阈值）。
     */
    fun search(source: Bitmap): Match? {
        val (image, points) = largestNative(source) ?: return null
        val similarity = FloatArray(1)
        val index = database.Query(image, points, similarity)
        return if (index >= 0) Match(index, similarity[0]) else null
    }

    /** 同 [search]，但吃已定位的人脸（相机流路径，免重复检测）。 */
    fun search(source: Bitmap, face: Rect, landmarks: List<PointF>): Match? =
        search(source.toSeetaImageData(), face, landmarks)

    /** 同上，但吃已转换的 native 图像 —— 相机流每帧只做一次 Bitmap 转换。 */
    fun search(image: com.seeta.sdk.SeetaImageData, face: Rect, landmarks: List<PointF>): Match? {
        val points = nativePoints(face, landmarks)
        val similarity = FloatArray(1)
        val index = database.Query(image, points, similarity)
        return if (index >= 0) Match(index, similarity[0]) else null
    }

    private fun nativeFace(
        source: Bitmap,
        face: Rect,
        landmarks: List<PointF>,
    ): Pair<com.seeta.sdk.SeetaImageData, Array<SeetaPointF>> =
        source.toSeetaImageData() to nativePoints(face, landmarks)

    private fun nativePoints(face: Rect, landmarks: List<PointF>): Array<SeetaPointF> {
        require(landmarks.size == 5) { "需要 5 点关键点集（face_landmarker_pts5）" }
        return Array(landmarks.size) { i ->
            SeetaPointF().apply {
                x = landmarks[i].x.toDouble()
                y = landmarks[i].y.toDouble()
            }
        }
    }

    /**
     * 返回与 [source] 最大人脸相似度不低于 [threshold] 的全部已注册条目，
     * 按相似度从高到低，最多 [limit] 条。
     */
    fun searchAll(source: Bitmap, threshold: Float, limit: Int = 10): List<Match> {
        val (image, points) = largestNative(source) ?: return emptyList()
        val index = LongArray(limit)
        val similarity = FloatArray(limit)
        val found = database.QueryAbove(image, points, threshold, limit.toLong(), index, similarity)
        return (0 until found.toInt()).map { Match(index[it], similarity[it]) }
    }

    /** 两张位图各自最大人脸的 1:1 比对；任一方无人脸时返回 null。 */
    fun compare(source1: Bitmap, source2: Bitmap): Float? {
        val (image1, points1) = largestNative(source1) ?: return null
        val (image2, points2) = largestNative(source2) ?: return null
        return database.Compare(image1, points1, image2, points2)
    }

    /** 已注册人脸数量。 */
    val count: Long get() = database.Count()

    /** 删除单个条目；索引不存在时返回 false。 */
    fun remove(index: Long): Boolean = database.Delete(index) != 0

    /** 清空全库。 */
    fun clear() = database.Clear()

    fun save(path: File): Boolean =
        database.Save(path.absolutePath).also {
            if (it) Log.i(TAG, "人脸库已落盘 ${path.name}") else Log.w(TAG, "人脸库落盘失败 ${path.name}")
        }

    fun load(path: File): Boolean =
        database.Load(path.absolutePath).also {
            // false 多为首次安装还没有库文件，属正常路径，不打 warn。
            Log.i(TAG, "人脸库加载 ${path.name} → $it")
        }

    override fun close() {
        database.dispose()
        landmarker.dispose()
        detector.dispose()
        Log.d(TAG, "人脸库与配套检测/关键点引擎已释放")
    }

    private companion object {
        const val TAG = "SeetaFaceDatabase"
    }
}
