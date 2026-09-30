// SeetaFacePipeline.kt —— SeetaFace6 组件门面：按需启用检测/关键点/识别/
// 活体/属性等组件，一次 detect 返回每张脸的全部结果。

package cn.heavenlybook.sf.face

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.seeta.sdk.AgePredictor
import com.seeta.sdk.EyeStateDetector
import com.seeta.sdk.FaceAntiSpoofing
import com.seeta.sdk.FaceDetector
import com.seeta.sdk.FaceLandmarker
import com.seeta.sdk.FaceRecognizer
import com.seeta.sdk.GenderPredictor
import com.seeta.sdk.MaskDetector
import com.seeta.sdk.SeetaDevice
import com.seeta.sdk.SeetaModelSetting
import com.seeta.sdk.SeetaPointF
import com.seeta.sdk.SeetaRect
import java.io.File

/** 口罩检测结果：[MaskResult.masked] 即官方文档的 score > 0.5 判定。 */
data class MaskResult(val masked: Boolean, val score: Float)

/** 左右眼状态；left 指画面左侧的那只眼。 */
data class EyeStates(val left: EyeStateDetector.EYE_STATE, val right: EyeStateDetector.EYE_STATE)

/**
 * 一张检出的人脸，携带所有已启用组件的产出。
 *
 * 未启用对应组件时字段为 null：[landmarks] 需要关键点器、[feature] 需要
 * 识别器、[spoof] 需要活体实例、[age]/[gender]/[eyes] 各需要对应预测器
 * （且依赖关键点器）、[mask] 只需口罩检测器。
 */
class SeetaFaceResult(
    val rect: Rect,
    val landmarks: List<PointF>?,
    val feature: FloatArray?,
    val spoof: FaceAntiSpoofing.Status?,
    val age: Int? = null,
    val gender: GenderPredictor.GENDER? = null,
    val eyes: EyeStates? = null,
    val mask: MaskResult? = null,
)

/**
 * 开箱即用的 SeetaFace6 组件门面：用 [Builder] 创建，对位图调 [detect]，
 * 用完 [close]。
 *
 * 实例持有 native 资源且**非线程安全**：每线程一条流水线，或由外部加锁
 * （如 app 侧的 nativeLock）。检测结果按置信度排序、不按图中位置排序；
 * 要取主导人脸请用 [detectLargest]。
 */
class SeetaFacePipeline internal constructor(
    private val detector: FaceDetector?,
    private val landmarker: FaceLandmarker?,
    private val recognizer: FaceRecognizer?,
    private val antiSpoofing: FaceAntiSpoofing?,
    private val agePredictor: AgePredictor?,
    private val genderPredictor: GenderPredictor?,
    private val eyeStateDetector: EyeStateDetector?,
    private val maskDetector: MaskDetector?,
    private val antiSpoofingVideoFrameCount: Int,
) : AutoCloseable {

    class Builder(private val modelsDir: File) {

        private var device: SeetaDevice = SeetaDevice.SEETA_DEVICE_AUTO
        private var deviceId: Int = 0
        private var detectorConfig: DetectorConfig? = null
        private var landmarkerModel: String? = null
        private var recognizerModel: String? = null
        private var antiSpoofingModels: Pair<String, String>? = null
        private var antiSpoofingVideoFrameCount: Int = 0
        private var antiSpoofingClarityThreshold: Float = 0.3f
        private var antiSpoofingRealityThreshold: Float = 0.8f
        private var ageModel: String? = null
        private var genderModel: String? = null
        private var eyeStateModel: String? = null
        private var maskModel: String? = null

        /** 运行时设备选择，默认 [SeetaDevice.SEETA_DEVICE_AUTO]。 */
        fun device(device: SeetaDevice, id: Int = 0): Builder = apply {
            this.device = device
            this.deviceId = id
        }

        /**
         * 启用人脸检测。`minFaceSize` 是最小可检人脸（像素），在业务允许的
         * 前提下尽量调大（80 相比默认 40/20 能快数倍）。大于
         * `maxImageWidth` x `maxImageHeight` 的图（0 = 上游默认 2000）会先
         * 被检测器降采样再分析。
         */
        fun withDetector(
            minFaceSize: Int = 40,
            threshold: Double = 0.9,
            maxImageWidth: Int = 0,
            maxImageHeight: Int = 0,
        ): Builder = apply {
            detectorConfig = DetectorConfig(minFaceSize, threshold, maxImageWidth, maxImageHeight)
        }

        /** 启用关键点；识别器、活体、年龄、性别、眼状态都以它为前置。 */
        fun withLandmarker(model: String = "face_landmarker_pts5.csta"): Builder = apply {
            landmarkerModel = model
        }

        /** 启用特征提取与相似度比对。 */
        fun withRecognizer(model: String = "face_recognizer.csta"): Builder = apply {
            recognizerModel = model
        }

        /**
         * 启用活体检测（需要官方两个 fas 模型，本地在前、全局在后，顺序不能
         * 颠倒）。`videoFrameCount > 0` 时 [SeetaFacePipeline.detect] 走视频
         * 模式（`PredictVideo`），融合满该帧数前返回
         * `FaceAntiSpoofing.Status.DETECTING`；0 保持单帧模式。
         * `clarityThreshold`/`realityThreshold` 对应上游 `SetThreshold`
         * （官方默认 0.3/0.8）：先过清晰度门限，现实分达到阈值判真人。
         */
        fun withAntiSpoofing(
            first: String = "fas_first.csta",
            second: String = "fas_second.csta",
            videoFrameCount: Int = 0,
            clarityThreshold: Float = 0.3f,
            realityThreshold: Float = 0.8f,
        ): Builder = apply {
            antiSpoofingModels = first to second
            antiSpoofingVideoFrameCount = videoFrameCount
            antiSpoofingClarityThreshold = clarityThreshold
            antiSpoofingRealityThreshold = realityThreshold
        }

        /** 启用年龄估计（需要关键点器）。 */
        fun withAge(model: String = "age_predictor.csta"): Builder = apply { ageModel = model }

        /** 启用性别估计（需要关键点器）。 */
        fun withGender(model: String = "gender_predictor.csta"): Builder = apply { genderModel = model }

        /** 启用睁闭眼检测（需要关键点器）。 */
        fun withEyeState(model: String = "eye_state.csta"): Builder = apply { eyeStateModel = model }

        /** 启用口罩检测（只需检测器，不依赖关键点）。 */
        fun withMask(model: String = "mask_detector.csta"): Builder = apply { maskModel = model }

        fun build(): SeetaFacePipeline {
            val needsLandmarks = recognizerModel != null || antiSpoofingModels != null ||
                ageModel != null || genderModel != null || eyeStateModel != null
            require(!needsLandmarks || landmarkerModel != null) {
                "识别/活体/年龄/性别/眼状态都会消费关键点，请先调用 withLandmarker(...)"
            }

            fun models(vararg names: String) = SeetaModelSetting(
                deviceId, names.map { File(modelsDir, it).absolutePath }.toTypedArray(), device
            )

            val detector = detectorConfig?.let {
                FaceDetector(models("face_detector.csta")).apply {
                    set(FaceDetector.Property.PROPERTY_MIN_FACE_SIZE, it.minFaceSize.toDouble())
                    set(FaceDetector.Property.PROPERTY_THRESHOLD, it.threshold)
                    if (it.maxImageWidth > 0) {
                        set(FaceDetector.Property.PROPERTY_MAX_IMAGE_WIDTH, it.maxImageWidth.toDouble())
                    }
                    if (it.maxImageHeight > 0) {
                        set(FaceDetector.Property.PROPERTY_MAX_IMAGE_HEIGHT, it.maxImageHeight.toDouble())
                    }
                }
            }
            val landmarker = landmarkerModel?.let { FaceLandmarker(models(it)) }
            val recognizer = recognizerModel?.let { FaceRecognizer(models(it)) }
            val antiSpoofing = antiSpoofingModels?.let { FaceAntiSpoofing(models(it.first, it.second)) }
                ?.apply {
                    SetThreshold(antiSpoofingClarityThreshold, antiSpoofingRealityThreshold)
                    if (antiSpoofingVideoFrameCount > 0) {
                        SetVideoFrameCount(antiSpoofingVideoFrameCount)
                    }
                }
            val agePredictor = ageModel?.let { AgePredictor(models(it)) }
            val genderPredictor = genderModel?.let { GenderPredictor(models(it)) }
            val eyeStateDetector = eyeStateModel?.let { EyeStateDetector(models(it)) }
            val maskDetector = maskModel?.let { MaskDetector(models(it)) }

            // 一次性节点日志：列出本次实际启用的组件，便于对齐配置与排查。
            val enabled = buildList {
                if (detector != null) add("detector")
                if (landmarker != null) add("landmarker")
                if (recognizer != null) add("recognizer")
                if (antiSpoofing != null) {
                    add(if (antiSpoofingVideoFrameCount > 0) "antiSpoofing(video=${antiSpoofingVideoFrameCount})" else "antiSpoofing(single)")
                }
                if (agePredictor != null) add("age")
                if (genderPredictor != null) add("gender")
                if (eyeStateDetector != null) add("eyeState")
                if (maskDetector != null) add("mask")
            }
            Log.i(TAG, "流水线构建完成 启用组件=${enabled.joinToString(",")}")

            return SeetaFacePipeline(
                detector, landmarker, recognizer, antiSpoofing,
                agePredictor, genderPredictor, eyeStateDetector, maskDetector,
                antiSpoofingVideoFrameCount,
            )
        }
    }

    private data class DetectorConfig(
        val minFaceSize: Int,
        val threshold: Double,
        val maxImageWidth: Int,
        val maxImageHeight: Int,
    )

    /**
     * 对 [source] 中每张人脸跑所有已启用组件，逐脸返回一个 [SeetaFaceResult]。
     */
    fun detect(source: Bitmap): List<SeetaFaceResult> {
        val detector = checkNotNull(detector) { "请先通过 withDetector(...) 启用检测器" }
        val image = source.toSeetaImageData()
        val rects = detector.Detect(image) ?: return emptyList()
        return rects.map { analyzeFace(image, it) }
    }

    /**
     * 对一个已定位的人脸（如相机流中 [SeetaFaceTracker] 的产出）执行已启用
     * 组件。任一依赖关键点的组件启用时必须先启用关键点器。
     */
    fun analyze(source: Bitmap, face: Rect): SeetaFaceResult? =
        analyze(source.toSeetaImageData(), face)

    /** 同 [analyze]，但吃已转换的 native 图像，免去同一帧的重复 Bitmap 转换。 */
    fun analyze(image: com.seeta.sdk.SeetaImageData, face: Rect): SeetaFaceResult? {
        val landmarker = this.landmarker
            ?: error("analyze(source, face) 需要先调用 withLandmarker(...)")
        val rect = SeetaRect().apply {
            x = face.left
            y = face.top
            width = face.width()
            height = face.height()
        }
        return analyzeFace(image, rect, landmarker)
    }

    private fun analyzeFace(
        image: com.seeta.sdk.SeetaImageData,
        rect: SeetaRect,
    ): SeetaFaceResult = analyzeFace(image, rect, landmarker)

    /** 单张人脸的组件执行序列；detect/analyze 的每帧热路径，不做日志。 */
    private fun analyzeFace(
        image: com.seeta.sdk.SeetaImageData,
        rect: SeetaRect,
        landmarker: FaceLandmarker?,
    ): SeetaFaceResult {
        val points = landmarker?.markPoints(image, rect)
        val feature = recognizer?.let { r ->
            FloatArray(r.GetExtractFeatureSize()).also {
                r.Extract(image, points, it)
            }
        }
        val spoof = antiSpoofing?.let {
            if (antiSpoofingVideoFrameCount > 0) {
                it.PredictVideo(image, rect, points)
            } else {
                it.Predict(image, rect, points)
            }
        }
        val age = agePredictor?.let { p ->
            IntArray(1).also { a -> p.PredictAgeWithCrop(image, points, a) }[0]
        }
        val gender = genderPredictor?.let { p ->
            // Java 侧是原地填充数组，先放入非空占位元素。
            Array(1) { GenderPredictor.GENDER.MALE }.also { g ->
                p.PredictGenderWithCrop(image, points, g)
            }[0]
        }
        val eyes = eyeStateDetector?.let { e ->
            Array(2) { EyeStateDetector.EYE_STATE.EYE_UNKNOWN }.also { s ->
                e.Detect(image, points, s)
            }.let { EyeStates(it[0], it[1]) }
        }
        val mask = maskDetector?.let { m ->
            val scores = FloatArray(1)
            val masked = m.detect(image, rect, scores)
            MaskResult(masked, scores[0])
        }
        return SeetaFaceResult(
            rect = Rect(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height),
            landmarks = points?.map { PointF(it.x.toFloat(), it.y.toFloat()) },
            feature = feature,
            spoof = spoof,
            age = age,
            gender = gender,
            eyes = eyes,
            mask = mask,
        )
    }

    /** [source] 中的主导（最宽）人脸；没有则返回 null。 */
    fun detectLargest(source: Bitmap): SeetaFaceResult? =
        detect(source).maxByOrNull { it.rect.width() }

    /**
     * 最近一帧活体判定的 clarity/reality 分数，供活体阈值调优；未启用活体
     * 时返回 null。
     */
    fun spoofScores(): Pair<Float, Float>? {
        val fas = antiSpoofing ?: return null
        val clarity = FloatArray(1)
        val reality = FloatArray(1)
        fas.GetPreFrameScore(clarity, reality)
        return clarity[0] to reality[0]
    }

    /** 让视频活体模式从新的一段视频开始统计。 */
    fun resetVideo() {
        antiSpoofing?.ResetVideo()
    }

    /** 两个 [detect] 产出特征的相似度；≥ [RECOMMENDED_SIMILARITY_THRESHOLD] 通常判定为同一人。 */
    fun compare(first: FloatArray, second: FloatArray): Float {
        val r = checkNotNull(recognizer) { "请先通过 withRecognizer(...) 启用识别器" }
        return r.CalculateSimilarity(first, second)
    }

    override fun close() {
        // 上游 dispose() 是幂等的（内部对 impl == 0 有保护）。
        detector?.dispose()
        landmarker?.dispose()
        recognizer?.dispose()
        antiSpoofing?.dispose()
        agePredictor?.dispose()
        genderPredictor?.dispose()
        eyeStateDetector?.dispose()
        maskDetector?.dispose()
        Log.d(TAG, "流水线已释放")
    }

    private fun FaceLandmarker.markPoints(image: com.seeta.sdk.SeetaImageData, rect: SeetaRect): Array<SeetaPointF> =
        Array(number()) { SeetaPointF() }.also { mark(image, rect, it) }

    companion object {
        /** 官方对默认 `face_recognizer.csta` 模型推荐的 1:1 相似度阈值。 */
        const val RECOMMENDED_SIMILARITY_THRESHOLD = 0.62f

        private const val TAG = "SeetaFacePipeline"
    }
}
