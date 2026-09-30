// SeetaQuality.kt —— 质量评估门面：对一张人脸批量跑已启用的质量项
// （亮度/清晰度/完整性/姿态/分辨率/光噪模糊），统一产出档位 + 分数。

package cn.heavenlybook.sf.face

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.seeta.sdk.FaceDetector
import com.seeta.sdk.FaceLandmarker
import com.seeta.sdk.QualityOfBirghtness
import com.seeta.sdk.QualityOfClarity
import com.seeta.sdk.QualityOfIntegrity
import com.seeta.sdk.QualityOfLBN
import com.seeta.sdk.QualityOfPose
import com.seeta.sdk.QualityOfPoseEx
import com.seeta.sdk.QualityOfResolution
import com.seeta.sdk.SeetaModelSetting
import com.seeta.sdk.SeetaPointF
import com.seeta.sdk.SeetaRect
import java.io.File

/** 质量档位，对应上游各评估器一致的 `QualityLevel`。 */
enum class SeetaQualityLevel { LOW, MEDIUM, HIGH }

/** 单个质量项的结果：档位 + 该项特有的分数（越高越好）。 */
data class SeetaQualityOutcome(val level: SeetaQualityLevel, val score: Float)

/** 深度模型 `quality_lbn.csta` 产出的光照/模糊/噪点原始状态。 */
data class LbnStates(
    val light: QualityOfLBN.LIGHTSTATE,
    val blur: QualityOfLBN.BLURSTATE,
    val noise: QualityOfLBN.NOISESTATE,
)

private const val TAG = "SeetaQuality"

/**
 * 组合式质量评估器：对一张人脸执行所有已启用的检查项。典型 1:N 流水线要求
 * 各项达到 `HIGH`（至少 `MEDIUM`）才采信该帧特征 —— 配合
 * [SeetaFaceTracker] 可为每个人挑出最佳帧。
 *
 * 上游默认阈值与其他 SeetaFace 模块开箱即配，Builder 暴露了常用的几项。
 * 注意 [SeetaQuality.Builder.withLightBlurNoise] 需要 68 点关键点模型
 * （`face_landmarker_pts68.csta`），其余项都只需 5 点。
 */
class SeetaQuality private constructor(
    private val detector: FaceDetector,
    private val landmarker: FaceLandmarker,
    private val checks: List<NamedCheck>,
    private val lbn: QualityOfLBN?,
    private val landmarker68: FaceLandmarker?,
) : AutoCloseable {

    enum class Item { BRIGHTNESS, CLARITY, INTEGRITY, POSE, POSE_EX, RESOLUTION, LIGHT_BLUR_NOISE }

    /** 一个质量项 = 评估闭包 + 对应 native 评估器的释放闭包。 */
    private class NamedCheck(
        val item: Item,
        val evaluate: (image: com.seeta.sdk.SeetaImageData, face: SeetaRect, points: Array<SeetaPointF>) -> SeetaQualityOutcome,
        val close: () -> Unit,
    )

    class Builder(private val modelsDir: File) {

        private var device: com.seeta.sdk.SeetaDevice = com.seeta.sdk.SeetaDevice.SEETA_DEVICE_AUTO
        private var deviceId: Int = 0
        private var minFaceSize: Int = 40
        private var brightness: FloatArray? = null
        private var clarity: FloatArray? = null
        private var integrity: FloatArray? = null
        private var withPose: Boolean = false
        private var poseExModel: String? = null
        private var resolution: FloatArray? = null
        private var lbnModel: String? = null
        private var lbnBlurThresh: Float = 0.8f
        private var landmarker68Model: String = "face_landmarker_pts68.csta"

        fun device(device: com.seeta.sdk.SeetaDevice, id: Int = 0): Builder = apply {
            this.device = device
            deviceId = id
        }

        fun withDetector(minFaceSize: Int = 40): Builder = apply { this.minFaceSize = minFaceSize }

        /** 亮度均匀性；上游默认阈值为 (70, 100, 210, 230)。 */
        fun withBrightness(v0: Float = 70f, v1: Float = 100f, v2: Float = 210f, v3: Float = 230f): Builder =
            apply { brightness = floatArrayOf(v0, v1, v2, v3) }

        /** 传统模糊度清晰度；上游默认阈值为 (0.1, 0.2)。 */
        fun withClarity(low: Float = 0.1f, high: Float = 0.2f): Builder =
            apply { clarity = floatArrayOf(low, high) }

        /** 人脸是否完整在画面内；上游默认阈值为 (10 px, 1.5 倍)。 */
        fun withIntegrity(low: Float = 10f, high: Float = 1.5f): Builder =
            apply { integrity = floatArrayOf(low, high) }

        /** 基于 5 点关键点的正脸姿态检查，无需额外模型。 */
        fun withPose(): Builder = apply { withPose = true }

        /** 深度姿态回归（偏航/俯仰/翻滚）；需要 `pose_estimation.csta`。 */
        fun withPoseEx(model: String = "pose_estimation.csta"): Builder = apply { poseExModel = model }

        /** 人脸分辨率检查；上游默认阈值为 (80, 120) px。 */
        fun withResolution(low: Float = 80f, high: Float = 120f): Builder =
            apply { resolution = floatArrayOf(low, high) }

        /**
         * 深度光噪/模糊/噪点分析；需要 `quality_lbn.csta` 加一个 68 点关键点
         * 模型。结果通过 [Item.LIGHT_BLUR_NOISE] 与 [checkLightBlurNoise] 暴露。
         */
        fun withLightBlurNoise(
            model: String = "quality_lbn.csta",
            blurThresh: Float = 0.8f,
            landmarker68Model: String = "face_landmarker_pts68.csta",
        ): Builder = apply {
            lbnModel = model
            lbnBlurThresh = blurThresh
            this.landmarker68Model = landmarker68Model
        }

        fun build(): SeetaQuality {
            fun models(vararg names: String) = SeetaModelSetting(
                deviceId, names.map { File(modelsDir, it).absolutePath }.toTypedArray(), device
            )

            val detector = FaceDetector(models("face_detector.csta")).apply {
                set(FaceDetector.Property.PROPERTY_MIN_FACE_SIZE, minFaceSize.toDouble())
            }
            val landmarker = FaceLandmarker(models("face_landmarker_pts5.csta"))
            val checks = mutableListOf<NamedCheck>()

            // 统一打包：评估闭包绑定评估器实例，释放闭包负责 dispose。
            fun <Q : Any> addCheck(item: Item, assessor: Q, dispose: Q.() -> Unit): NamedCheck = NamedCheck(
                item,
                { image, face, points -> outcome(assessor, image, face, points) },
                { assessor.dispose() },
            )

            brightness?.let { v ->
                checks += if (v.size == 4) {
                    addCheck(Item.BRIGHTNESS, QualityOfBirghtness(v[0], v[1], v[2], v[3])) { dispose() }
                } else {
                    addCheck(Item.BRIGHTNESS, QualityOfBirghtness()) { dispose() }
                }
            }
            clarity?.let { v -> checks += addCheck(Item.CLARITY, QualityOfClarity(v[0], v[1])) { dispose() } }
            integrity?.let { v -> checks += addCheck(Item.INTEGRITY, QualityOfIntegrity(v[0], v[1])) { dispose() } }
            if (withPose) checks += addCheck(Item.POSE, QualityOfPose()) { dispose() }
            poseExModel?.let { model ->
                checks += addCheck(Item.POSE_EX, QualityOfPoseEx(models(model))) { dispose() }
            }
            resolution?.let { v -> checks += addCheck(Item.RESOLUTION, QualityOfResolution(v[0], v[1])) { dispose() } }

            val lbn = lbnModel?.let { model ->
                QualityOfLBN(models(model)).apply {
                    set(QualityOfLBN.Property.PROPERTY_BLUR_THRESH, lbnBlurThresh.toDouble())
                }
            }
            val landmarker68 = lbn?.let { FaceLandmarker(models(landmarker68Model)) }

            Log.i(
                TAG,
                "质量引擎构建完成 启用项=${checks.joinToString("/") { it.item.name }}" +
                    if (lbn != null) "/LIGHT_BLUR_NOISE" else "",
            )
            return SeetaQuality(detector, landmarker, checks, lbn, landmarker68)
        }

        private companion object {
            /** 上游各评估器内嵌完全一致的 LOW/MEDIUM/HIGH 枚举，这里统一分发取值。 */
            fun outcome(
                assessor: Any,
                image: com.seeta.sdk.SeetaImageData,
                face: SeetaRect,
                points: Array<SeetaPointF>,
            ): SeetaQualityOutcome {
                val score = FloatArray(1)
                val level = when (assessor) {
                    is QualityOfBirghtness -> assessor.check(image, face, points, score)
                    is QualityOfClarity -> assessor.check(image, face, points, score)
                    is QualityOfIntegrity -> assessor.check(image, face, points, score)
                    is QualityOfPose -> assessor.check(image, face, points, score)
                    is QualityOfPoseEx -> assessor.check(image, face, points, score)
                    is QualityOfResolution -> assessor.check(image, face, points, score)
                    else -> error("未知的评估器类型 $assessor")
                }
                return SeetaQualityOutcome(SeetaQualityLevel.entries[level.ordinal], score[0])
            }
        }
    }

    /**
     * 对 [source] 的最大人脸执行所有已启用项。
     *
     * @return 以质量项为键的结果表；未检到人脸时返回空表。
     * [Item.LIGHT_BLUR_NOISE] 的档位反映模糊状态（模糊 = LOW）、分数恒为 0
     * —— 需要原始状态请另用 [checkLightBlurNoise]。
     */
    fun check(source: Bitmap): Map<Item, SeetaQualityOutcome> {
        val image = source.toSeetaImageData()
        val rect = detector.Detect(image)?.maxByOrNull { it.width } ?: return emptyMap()
        val points = Array(landmarker.number()) { SeetaPointF() }
        landmarker.mark(image, rect, points)
        return evaluate(image, rect, points)
    }

    /**
     * 同 [check]，但复用已有检测结果（如 [SeetaFacePipeline.detect] 的产出）
     * —— [face] 与 [landmarks] 必须属于同一张位图，且 [landmarks] 须为
     * 5 点集。
     */
    fun check(source: Bitmap, face: Rect, landmarks: List<PointF>): Map<Item, SeetaQualityOutcome> {
        require(landmarks.size == 5) { "质量评估需要 5 点关键点集" }
        val image = source.toSeetaImageData()
        val rect = SeetaRect().apply {
            x = face.left
            y = face.top
            width = face.width()
            height = face.height()
        }
        val points = Array(landmarks.size) { i ->
            SeetaPointF().apply {
                x = landmarks[i].x.toDouble()
                y = landmarks[i].y.toDouble()
            }
        }
        return evaluate(image, rect, points)
    }

    /**
     * [source] 最大人脸的光照/模糊/噪点状态；未启用
     * [SeetaQuality.Builder.withLightBlurNoise] 时返回 null，未检到人脸亦然。
     */
    fun checkLightBlurNoise(source: Bitmap): LbnStates? {
        val l = lbn ?: return null
        val m68 = checkNotNull(landmarker68)
        val image = source.toSeetaImageData()
        val rect = detector.Detect(image)?.maxByOrNull { it.width } ?: return null
        val points68 = Array(m68.number()) { SeetaPointF() }
        m68.mark(image, rect, points68)
        val light = IntArray(1)
        val blur = IntArray(1)
        val noise = IntArray(1)
        l.Detect(image, points68, light, blur, noise)
        return LbnStates(
            light = QualityOfLBN.LIGHTSTATE.entries[light[0]],
            blur = QualityOfLBN.BLURSTATE.entries[blur[0]],
            noise = QualityOfLBN.NOISESTATE.entries[noise[0]],
        )
    }

    override fun close() {
        checks.forEach { it.close() }
        lbn?.dispose()
        landmarker68?.dispose()
        landmarker.dispose()
        detector.dispose()
        Log.d(TAG, "质量引擎已释放")
    }

    private fun evaluate(
        image: com.seeta.sdk.SeetaImageData,
        rect: SeetaRect,
        points: Array<SeetaPointF>,
    ): Map<Item, SeetaQualityOutcome> =
        checks.associate { it.item to it.evaluate(image, rect, points) }
}
