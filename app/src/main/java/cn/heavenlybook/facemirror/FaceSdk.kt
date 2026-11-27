// FaceSdk.kt —— 引擎聚合器：按当前设置构建并持有流水线、质量评估、人脸库等
// 原生引擎实例，供三个 Tab 共享；native 句柄存活至 [close]。

package cn.heavenlybook.facemirror

import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * 各 Tab 共享的引擎集合；native 句柄存活至 [close]。
 *
 * @param minFaceSize 最小可检人脸（像素），来自 [AppSettings] —— 引擎构建后不可改，下次启动生效。
 * @param inferThreads 人脸库的推理线程数（0 = 用上游默认值）—— 同样仅启动时生效。
 * @param recognizerModel 用哪个识别模型构建；每个模型自带独立人脸库，
 *   切换模型不会销毁已注册数据 —— 仅启动时生效。
 */
internal class FaceSdk(
    val modelsDir: File,
    filesDir: File,
    minFaceSize: Int,
    inferThreads: Int,
    recognizerModel: String,
    livenessRealityThreshold: Float,
) : AutoCloseable {

    private val recognizer = if (File(modelsDir, recognizerModel).isFile) {
        recognizerModel
    } else {
        Log.w(TAG, "识别模型 $recognizerModel 文件不存在，回退默认模型 ${AppSettings.DEFAULT_RECOGNIZER_MODEL}")
        AppSettings.DEFAULT_RECOGNIZER_MODEL
    }

    /**
     * 本 Demo 的流水线实际会加载的模型。缺任何一个都在构造 native 引擎前
     * 明确报错，避免底层只抛出难以定位的 JNI 异常。
     */
    private val requiredModels = listOf(
        "face_detector.csta",
        "face_landmarker_pts5.csta",
        recognizer,
        "fas_first.csta",
        "fas_second.csta",
        "age_predictor.csta",
        "gender_predictor.csta",
        "eye_state.csta",
        "mask_detector.csta",
    )

    init {
        val missing = requiredModels.filterNot { File(modelsDir, it).let { f -> f.isFile && f.length() > 0 } }
        if (missing.isNotEmpty()) {
            val message = "模型不完整，缺少：${missing.joinToString()}（目录：${modelsDir.path}）"
            val error = IOException(message)
            Log.e(TAG, message, error)
            throw error
        }
        Log.i(TAG, "必需模型检查通过 count=${requiredModels.size}")
    }

    init {
        // TenniS/OpenMP：给 libomp 工作线程留出装得下（Debug 构建中体积巨大的）
        // 推理帧的栈空间。必须先于第一个 SeetaFace 库加载设置。
        android.system.Os.setenv("OMP_STACKSIZE", "16M", true)
    }

    /**
     * 推理固定跑在这个专用线程池上：线程栈 16 MB —— Dispatchers.IO 的线程
     * 只有约 1 MB 栈，会在 TenniS 内核里栈溢出（libtennis.so 的 omp_outlined
     * 区域内 SIGSEGV）。
     */
    val inferenceDispatcher: CoroutineDispatcher by lazy {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2) { runnable ->
            Thread(null, runnable, "seeta-inference", 16L * 1024 * 1024).apply { isDaemon = true }
        }
        Log.d(TAG, "推理线程池已创建（2 线程 × 16MB 栈）")
        pool.asCoroutineDispatcher()
    }

    val pipeline = cn.heavenlybook.sf.face.SeetaFacePipeline.Builder(modelsDir)
        .withDetector(minFaceSize = minFaceSize)
        .withLandmarker()
        .withRecognizer(recognizer)
        .withAntiSpoofing(realityThreshold = livenessRealityThreshold) // 单帧活体：本 demo 只处理静态图片
        .withAge().withGender().withEyeState().withMask()
        .build()
    val database = cn.heavenlybook.sf.face.SeetaFaceDatabase.Builder(modelsDir)
        .withDetector(minFaceSize = minFaceSize)
        .withRecognizer(recognizer)
        .apply { if (inferThreads > 0) withThreads(inferThreads, inferThreads) }
        .build()

    init {
        Log.i(
            TAG,
            "引擎构建完成 recognizer=$recognizer minFaceSize=$minFaceSize inferThreads=$inferThreads",
        )
    }

    /** 深度质量项依赖可选模型；两个模型都随 assets 发布，缺失时优雅降级。 */
    private val hasPoseEx = File(modelsDir, "pose_estimation.csta").isFile
    private val hasLbn = File(modelsDir, "quality_lbn.csta").isFile &&
        File(modelsDir, "face_landmarker_pts68.csta").isFile

    init {
        // 缺模型不报错，只降级并提示，UI 会据此隐藏对应质量项。
        if (!hasPoseEx) Log.i(TAG, "缺少 pose_estimation.csta，深度姿态质量项不可用")
        if (!hasLbn) Log.i(TAG, "缺少 quality_lbn.csta 或 68 点关键点模型，光噪/模糊质量项不可用")
    }

    val quality = cn.heavenlybook.sf.face.SeetaQuality.Builder(modelsDir)
        .withBrightness().withClarity().withIntegrity().withPose().withResolution()
        .apply { if (hasPoseEx) withPoseEx() }
        .apply { if (hasLbn) withLightBlurNoise() }
        .build()

    /** （深度姿态已启用, 光噪/模糊已启用）—— 模型缺失时供 UI 提示用。 */
    val advancedQuality: Pair<Boolean, Boolean> = hasPoseEx to hasLbn

    /** 人像图 + 显示名注册表，叠在只存整数索引特征向量的原生数据库之上。 */
    val gallery = FaceGalleryStore(filesDir, recognizer)

    /**
     * 独立 68 点关键点器：管线内的组件只吃 5 点模型，68 点只能这样单独供出，
     * 用于检测分析的密集轮廓展示。模型缺失时为 null（UI 降级为 5 点）。
     */
    val landmarker68 = if (File(modelsDir, "face_landmarker_pts68.csta").isFile) {
        runCatching {
            cn.heavenlybook.sf.face.SeetaFaceLandmarker.Builder(modelsDir).build()
        }.getOrNull()
    } else {
        null
    }

    /**
     * 串行化 [pipeline]/[quality]（以及相机的视频活体流水线）的使用：
     * 它们都非线程安全，且切 Tab 时可能仍有未结束的推理协程与相机分析线程
     * 竞争。[database] 内部自带串行化，不需要这把锁。
     */
    val nativeLock = Any()

    private val dbFile = File(filesDir, "gallery-${modelKey(recognizer)}.db")

    init {
        database.load(dbFile) // 人脸库文件不存在（首次安装）时由 native 侧容忍，直接按空库继续
        Log.d(TAG, "人脸库已加载 ${dbFile.name}")
    }

    fun saveGallery() {
        database.save(dbFile)
        Log.i(TAG, "人脸库已保存 ${dbFile.name}，登记 ${gallery.entries().size} 条")
    }

    private fun modelKey(model: String) = model.removeSuffix(".csta")

    override fun close() {
        val dispatcher = inferenceDispatcher as? ExecutorCoroutineDispatcher
        val pool = dispatcher?.executor as? java.util.concurrent.ExecutorService
        // shutdown() 不打断在途推理，让正在跑的任务自然结束，避免强杀线程时
        // native 层（TenniS 内核）崩溃；新任务则不再接收。
        pool?.shutdown()
        // 必须等在途任务退出后再释放 native 句柄：相机 Tab 的异步 1:N 检索就跑在
        // 这个池上，若 Query 尚未返回就 database.close()，native 侧会释放句柄后
        // 仍被查询线程访问（use-after-free）。3s 与相机线程的等待上限保持一致。
        try {
            if (pool != null && !pool.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.w(TAG, "推理线程池 3s 内未退出，继续释放 native 句柄；在途任务可能失败")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "等待推理线程池退出被中断，继续释放 native 句柄", e)
        }
        pipeline.close()
        database.close()
        quality.close()
        landmarker68?.close()
        Log.i(TAG, "FaceSdk 已关闭：流水线/人脸库/质量引擎与推理线程池均已释放")
    }

    private companion object {
        const val TAG = "FaceSdk"
    }
}
