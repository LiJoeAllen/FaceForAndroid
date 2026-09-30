package cn.heavenlybook.sf.face

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.seeta.sdk.FaceLandmarker
import com.seeta.sdk.SeetaDevice
import com.seeta.sdk.SeetaModelSetting
import com.seeta.sdk.SeetaPointF
import com.seeta.sdk.SeetaRect
import java.io.File

/**
 * Standalone facial-landmarker facade — the one SeetaFace6 component the
 * [SeetaFacePipeline] cannot expose itself: pipelines need the 5-point model
 * (every landmark-consuming component expects exactly 5 points), while the
 * 68-point model is only wired internally to [SeetaQuality]'s light/blur/noise
 * assessor. This class surfaces it for its own sake: dense contours for
 * visualization, cropping and any consumer that wants richer geometry.
 *
 * Instances hold native resources and are **not thread-safe** — use one per
 * thread or guard with external synchronization. Create via [Builder] and
 * [close] when done.
 */
class SeetaFaceLandmarker private constructor(
    private val landmarker: FaceLandmarker,
) : AutoCloseable {

    class Builder(private val modelsDir: File) {

        private var device: SeetaDevice = SeetaDevice.SEETA_DEVICE_AUTO
        private var deviceId: Int = 0
        private var model: String = "face_landmarker_pts68.csta"

        /** Runtime device selection, defaults to [SeetaDevice.SEETA_DEVICE_AUTO]. */
        fun device(device: SeetaDevice, id: Int = 0): Builder = apply {
            this.device = device
            deviceId = id
        }

        /**
         * Landmarker model to load; the official distribution ships
         * `face_landmarker_pts5.csta` (5 points, the one all pipeline
         * components require) and `face_landmarker_pts68.csta` (68 points,
         * dense contours). Default is the 68-point model — the 5-point one is
         * already reachable through [SeetaFacePipeline].
         */
        fun withModel(model: String = "face_landmarker_pts68.csta"): Builder = apply {
            this.model = model
        }

        fun build(): SeetaFaceLandmarker {
            val setting = SeetaModelSetting(
                deviceId,
                arrayOf(File(modelsDir, model).absolutePath),
                device,
            )
            return SeetaFaceLandmarker(FaceLandmarker(setting))
        }
    }

    /** Number of points this model produces (5 or 68 for the official models). */
    val pointCount: Int get() = landmarker.number()

    /**
     * Landmarks of [face] in [source], in image coordinates; null when
     * [face] lies outside [source].
     */
    fun mark(source: Bitmap, face: Rect): List<PointF>? {
        if (face.left < 0 || face.top < 0 ||
            face.right > source.width || face.bottom > source.height
        ) {
            return null
        }
        val image = source.toSeetaImageData()
        val rect = SeetaRect().apply {
            x = face.left
            y = face.top
            width = face.width()
            height = face.height()
        }
        val points = Array(landmarker.number()) { SeetaPointF() }
        landmarker.mark(image, rect, points)
        return points.map { PointF(it.x.toFloat(), it.y.toFloat()) }
    }

    override fun close() {
        landmarker.dispose()
    }
}
