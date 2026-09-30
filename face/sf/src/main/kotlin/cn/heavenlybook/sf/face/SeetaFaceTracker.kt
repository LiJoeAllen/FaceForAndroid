// SeetaFaceTracker.kt —— 视频流人脸跟踪门面：跨帧维持稳定人员 ID（pid），
// 让下游识别/分析按"人"而不是按"帧"执行。

package cn.heavenlybook.sf.face

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.seeta.sdk.FaceTracker
import com.seeta.sdk.SeetaImageData
import com.seeta.sdk.SeetaTrackingFaceInfo
import java.io.File

/** 一个被跟踪的人脸：[pid] 在视频流连续帧之间标识同一个人。 */
data class TrackedFace(val pid: Int, val rect: Rect, val score: Float)

/**
 * 视频流人脸跟踪：跨帧给人脸分配稳定的人员 ID（[TrackedFace.pid]），下游的
 * 识别/分析因此只需按人执行，不必每帧重跑。
 *
 * 跟踪器与构造时指定的视频分辨率绑定；与 SDK 其余组件一样**非线程安全**。
 * 同一路流的连续帧应使用同一个跟踪器实例；换流时需重建（JNI 绑定没有
 * 提供重置接口）。
 */
class SeetaFaceTracker @JvmOverloads constructor(
    modelsDir: File,
    val videoWidth: Int,
    val videoHeight: Int,
    detectorModel: String = "face_detector.csta",
) : AutoCloseable {

    private val tracker = FaceTracker(File(modelsDir, detectorModel).absolutePath, videoWidth, videoHeight)

    /**
     * 跟踪一帧中的人脸。连续帧之间同一个人保持相同的 [TrackedFace.pid]。
     * 每帧热路径，不做日志。
     */
    fun track(source: Bitmap, frameNo: Int = -1): List<TrackedFace> =
        track(source.toSeetaImageData(), frameNo)

    /** 同 [track]，但吃已转换的 native 图像 —— 相机流每帧只做一次 Bitmap 转换。 */
    fun track(image: SeetaImageData, frameNo: Int = -1): List<TrackedFace> {
        val infos: Array<SeetaTrackingFaceInfo> = when {
            frameNo >= 0 -> tracker.Track(image, frameNo)
            else -> tracker.Track(image)
        } ?: return emptyList()
        return infos.map { info ->
            TrackedFace(
                pid = info.PID,
                score = info.score,
                rect = Rect(info.x, info.y, info.x + info.width, info.y + info.height),
            )
        }
    }

    /** 最小可检人脸（像素）：调大检测更少但明显更快。 */
    var minFaceSize: Int
        get() = tracker.GetMinFaceSize()
        set(value) = tracker.SetMinFaceSize(value)

    /** 帧间平滑检测框，让叠加显示更稳。 */
    var videoStable: Boolean
        get() = tracker.GetVideoStable()
        set(value) = tracker.SetVideoStable(value)

    /** 限制单次 track 调用的内部并行度。 */
    fun setCalculationThreads(number: Int) = tracker.SetSingleCalculationThreads(number)

    override fun close() {
        tracker.dispose()
        Log.d("SeetaFaceTracker", "人脸跟踪器已关闭 ${videoWidth}x$videoHeight")
    }
}
