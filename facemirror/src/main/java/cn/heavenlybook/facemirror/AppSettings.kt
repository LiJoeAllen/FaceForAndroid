// AppSettings.kt —— 用户可调设置项：SharedPreferences 持久化，含生效时机说明。

package cn.heavenlybook.facemirror

import android.content.Context
import android.util.Log
import androidx.core.content.edit

/**
 * 用户可调的产品设置，持久化在 `SharedPreferences`。
 *
 * 生效时机分三档：引擎构建参数（`minFaceSize`、`inferThreads`、`recognizerModel`）
 * 下次启动生效；相机侧参数（`frontCamera`、`videoFrameCount`、`videoStable`）
 * 下次打开相机 Tab 生效；阈值类立即生效。每个识别模型独立人脸库（特征只在
 * 同一模型内可比），切换模型不会销毁已注册数据。
 */
data class AppSettings(
    val verifyThreshold: Float = DEFAULT_VERIFY_THRESHOLD,
    val searchThreshold: Float = DEFAULT_SEARCH_THRESHOLD,
    val frontCamera: Boolean = false,
    val minFaceSize: Int = DEFAULT_MIN_FACE_SIZE,
    val videoFrameCount: Int = DEFAULT_VIDEO_FRAME_COUNT,
    val videoStable: Boolean = DEFAULT_VIDEO_STABLE,
    val inferThreads: Int = 0,
    val recognizerModel: String = DEFAULT_RECOGNIZER_MODEL,
    val livenessRealityThreshold: Float = DEFAULT_LIVENESS_REALITY_THRESHOLD,
    val livenessMode: String = DEFAULT_LIVENESS_MODE,
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putFloat(KEY_VERIFY_THRESHOLD, verifyThreshold)
                .putFloat(KEY_SEARCH_THRESHOLD, searchThreshold)
                .putBoolean(KEY_FRONT_CAMERA, frontCamera)
                .putInt(KEY_MIN_FACE_SIZE, minFaceSize)
                .putInt(KEY_VIDEO_FRAME_COUNT, videoFrameCount)
                .putBoolean(KEY_VIDEO_STABLE, videoStable)
                .putInt(KEY_INFER_THREADS, inferThreads)
                .putString(KEY_RECOGNIZER_MODEL, recognizerModel)
                .putFloat(KEY_LIVENESS_REALITY_THRESHOLD, livenessRealityThreshold)
                .putString(KEY_LIVENESS_MODE, livenessMode)
        }
        Log.d(TAG, "设置已保存 verify=$verifyThreshold search=$searchThreshold model=$recognizerModel")
    }

    companion object {
        private const val TAG = "AppSettings"
        const val PREFS = "sf_settings"
        const val DEFAULT_VERIFY_THRESHOLD = 0.62f
        const val DEFAULT_SEARCH_THRESHOLD = 0.70f
        const val DEFAULT_MIN_FACE_SIZE = 80
        const val DEFAULT_VIDEO_FRAME_COUNT = 5

        /** 跟踪框帧间平滑(上游 SetVideoStable):默认开启,压掉逐帧检测抖动。 */
        const val DEFAULT_VIDEO_STABLE = true

        /** 相机活体模式:静默(单帧,快) / 视频(多帧融合,稳但贵约 190ms/次)。 */
        const val LIVENESS_SILENT = "silent"
        const val LIVENESS_VIDEO = "video"
        const val DEFAULT_LIVENESS_MODE = LIVENESS_SILENT

        /** assets 内置的三个识别模型：标准 / 轻量 / 口罩容忍。 */
        val RECOGNIZER_MODELS = linkedMapOf(
            "face_recognizer.csta" to "标准",
            "face_recognizer_light.csta" to "轻量(快)",
            "face_recognizer_mask.csta" to "口罩(戴口罩更准)",
        )
        const val DEFAULT_RECOGNIZER_MODEL = "face_recognizer.csta"

        /** 上游 SetThreshold 的官方默认现实分阈值（清晰度门限固定 0.3）。 */
        const val DEFAULT_LIVENESS_REALITY_THRESHOLD = 0.8f

        private const val KEY_VERIFY_THRESHOLD = "verify_threshold"
        private const val KEY_SEARCH_THRESHOLD = "search_threshold"
        private const val KEY_FRONT_CAMERA = "front_camera"
        private const val KEY_MIN_FACE_SIZE = "min_face_size"
        private const val KEY_VIDEO_FRAME_COUNT = "video_frame_count"
        private const val KEY_VIDEO_STABLE = "video_stable"
        private const val KEY_INFER_THREADS = "infer_threads"
        private const val KEY_RECOGNIZER_MODEL = "recognizer_model"
        private const val KEY_LIVENESS_REALITY_THRESHOLD = "liveness_reality_threshold"
        private const val KEY_LIVENESS_MODE = "liveness_mode"

        fun load(context: Context): AppSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AppSettings(
                verifyThreshold = p.getFloat(KEY_VERIFY_THRESHOLD, DEFAULT_VERIFY_THRESHOLD),
                searchThreshold = p.getFloat(KEY_SEARCH_THRESHOLD, DEFAULT_SEARCH_THRESHOLD),
                frontCamera = p.getBoolean(KEY_FRONT_CAMERA, false),
                minFaceSize = p.getInt(KEY_MIN_FACE_SIZE, DEFAULT_MIN_FACE_SIZE),
                videoFrameCount = p.getInt(KEY_VIDEO_FRAME_COUNT, DEFAULT_VIDEO_FRAME_COUNT),
                videoStable = p.getBoolean(KEY_VIDEO_STABLE, DEFAULT_VIDEO_STABLE),
                inferThreads = p.getInt(KEY_INFER_THREADS, 0),
                recognizerModel = p.getString(KEY_RECOGNIZER_MODEL, null)
                    ?: DEFAULT_RECOGNIZER_MODEL,
                livenessRealityThreshold = p.getFloat(
                    KEY_LIVENESS_REALITY_THRESHOLD,
                    DEFAULT_LIVENESS_REALITY_THRESHOLD,
                ),
                livenessMode = p.getString(KEY_LIVENESS_MODE, null) ?: DEFAULT_LIVENESS_MODE,
            ).also {
                Log.d(TAG, "设置已加载 model=${it.recognizerModel} minFace=${it.minFaceSize} 前置相机=${it.frontCamera}")
            }
        }
    }
}
