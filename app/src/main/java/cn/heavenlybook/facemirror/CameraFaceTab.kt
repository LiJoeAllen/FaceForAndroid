// CameraFaceTab.kt —— 实时相机识别 Tab：跟踪器维持稳定人员 ID，视频活体
// 流水线逐帧给出活体与关键点，人脸库按人限流做 1:N 重识别并在画面上标注。

package cn.heavenlybook.facemirror

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.heavenlybook.sf.face.Match
import cn.heavenlybook.sf.face.SeetaFacePipeline
import cn.heavenlybook.sf.face.SeetaFaceTracker
import cn.heavenlybook.sf.face.SeetaFrameBuffer
import cn.heavenlybook.sf.face.TrackedFace
import com.seeta.sdk.FaceAntiSpoofing
import com.seeta.sdk.SeetaImageData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import androidx.core.graphics.scale

/** 同一人两次 1:N 重识别的最小间隔：识别远贵于活体，按人限流保住帧率。 */
private const val RECOGNIZE_INTERVAL_MS = 1500L

/**
 * 全量识别流水线的全局最小间隔：pid 抖动会让人反复拿到"新 id"，逐个触发
 * 立即重识别 —— 不加这道间隔，~100ms 级的全量分析会在相邻帧连续叠加，
 * 帧率骤降。代价是新面孔最晚 300ms 后才出识别结果。
 */
private const val RECOGNIZE_GLOBAL_GAP_MS = 300L

/** 视频活体隔帧融合：徽标约 2-3 fps 刷新一次，不影响识别节奏。 */
private const val LIVENESS_EVERY_N_FRAMES = 2

/**
 * 隔帧跟踪：tracker.track 单帧约 30ms，是相机链路最大开销。每 2 帧跑一次，
 * 中间帧复用上一帧的框；预览仍逐帧刷新，框更新降到约 15fps。
 */
private const val TRACK_EVERY_N_FRAMES = 2L

private const val TAG = "CameraTab"

/** 跟踪框旁渲染的身份信息：名字 + 相似度 + 活体结论 + 人像小图。 */
private data class CamIdentity(
    val label: String,
    val similarity: Float?,
    val spoof: FaceAntiSpoofing.Status?,
    val portrait: Bitmap?,
)

/**
 * 异步 1:N 检索请求。image 必须是整帧 BGR 的独立拷贝：相机线程的
 * [SeetaFrameBuffer] 下一帧就会覆写原缓冲，直接传引用会让检索读到脏数据。
 */
private data class SearchRequest(
    val pid: Int,
    val version: Long,
    val image: SeetaImageData,
    val rect: Rect,
    val landmarks: List<PointF>,
    val spoof: FaceAntiSpoofing.Status?,
)

/** 异步检索结果；由相机线程 drain 后按 pid + version 丢弃过期项。 */
private data class SearchResult(
    val pid: Int,
    val version: Long,
    val hit: Match?,
    val spoof: FaceAntiSpoofing.Status?,
)

/**
 * 实时相机识别。[CameraAnalysisPreview] 送帧进来；[SeetaFaceTracker] 维护
 * 稳定的人员 ID；一条精简流水线（检测器 + 关键点 + 活体，不带识别器/属性）
 * 在视频模式逐帧融合活体、在静默模式为识别帧补算关键点与单帧活体；人脸库
 * 检索对每个人最多每 [RECOGNIZE_INTERVAL_MS] 重识别一次。精简流水线尚未
 * 构建完成时，识别退回共享的全量流水线。
 */
@Composable
internal fun CameraTab(
    engine: FaceSdk,
    settings: AppSettings,
    onFacingChange: (Boolean) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var status by remember { mutableStateOf("等待相机…") }
    val identities = remember { mutableStateMapOf<Int, CamIdentity>() }
    // 活体结论走分析线程私有的 map（只喂给画布，不进 Compose 状态）：
    // 每帧刷新它不触发身份列表重组，UI 侧只有标注位图每帧变化。
    val liveSpoof = remember { HashMap<Int, FaceAntiSpoofing.Status>() }
    val portraitCache = remember { HashMap<Long, Bitmap>() }
    val lastRecognizedAt = remember { ConcurrentHashMap<Int, Long>() }
    val hadFaces = remember { AtomicBoolean(false) }
    val frameCounter = remember { java.util.concurrent.atomic.AtomicLong(0) }
    // 分析线程独占的跟踪结果缓存：隔帧跟踪时中间帧直接复用，避免同一张脸
    // 每帧都重新跑检测。用 AtomicReference 只是为了跨重组保留引用。
    val trackCache = remember { AtomicReference<List<TrackedFace>>(emptyList()) }
    // 异步 1:N 检索：相机线程只负责拷贝并投递，database.search 在 16MB 栈的
    // 推理线程池执行。通道容量 1 + DROP_OLDEST：识别跟不上时丢旧请求，
    // 绝不把过期检索排到实时画面后面。结果由 drainSearchResults 在相机线程
    // 统一写回 identities，避免 Compose 状态被多线程并发修改。
    val searchQueue = remember {
        Channel<SearchRequest>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }
    val searchResults = remember { ConcurrentLinkedQueue<SearchResult>() }
    val recognizeVersion = remember { HashMap<Int, Long>() }
    // 只在分析线程使用：复用 Bitmap→BGR 的中间数组，消除逐帧 GC 压力。
    val frameBuffer = remember { SeetaFrameBuffer() }
    val lastFullRunAt = remember { java.util.concurrent.atomic.AtomicLong(0) }
    val profiler = remember { FrameProfiler() }

    val tracker = remember {
        SeetaFaceTracker(engine.modelsDir, ANALYSIS_W, ANALYSIS_H).apply {
            minFaceSize = settings.minFaceSize
            videoStable = settings.videoStable
            if (settings.inferThreads > 0) setCalculationThreads(settings.inferThreads)
        }.also {
            Log.i(TAG, "相机跟踪策略：每 $TRACK_EVERY_N_FRAMES 帧检测一次，中间帧复用跟踪框")
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "离开相机 Tab：关闭人脸跟踪器")
            tracker.close()
        }
    }

    // 精简流水线：检测器 + 关键点 + 活体，不带识别器/属性模型。视频模式按
    // N 帧融合逐帧跑活体；静默模式只在识别帧跑（单帧 Predict），为 1:N 检索
    // 供关键点与活体结论 —— 若退回共享全量流水线，特征/年龄/性别/眼态/口罩
    // 会被白白算一遍，识别尖峰贵近三倍。
    var livePipeline by remember { mutableStateOf<SeetaFacePipeline?>(null) }
    val videoMode = settings.livenessMode == AppSettings.LIVENESS_VIDEO
    LaunchedEffect(Unit) {
        // 若在构建途中离开 Tab，withContext 会抛 CancellationException，
        // 刚建好的 native 流水线就会泄漏 —— 这里显式关掉。
        var built: SeetaFacePipeline? = null
        try {
            withContext(Dispatchers.IO) {
                built = runCatching {
                    SeetaFacePipeline.Builder(engine.modelsDir)
                        .withDetector(minFaceSize = settings.minFaceSize)
                        .withLandmarker()
                        .withAntiSpoofing(
                            videoFrameCount = if (videoMode) settings.videoFrameCount else 0,
                            realityThreshold = settings.livenessRealityThreshold,
                        )
                        .build()
                }.onFailure {
                    Log.e(TAG, "精简活体流水线构建失败，将一直使用共享单帧流水线", it)
                }.getOrNull()
            }
            Log.i(TAG, "精简活体流水线就绪 videoMode=$videoMode frameCount=${settings.videoFrameCount}")
            livePipeline = built
        } catch (e: CancellationException) {
            built?.close()
            throw e
        }
    }
    DisposableEffect(Unit) {
        onDispose { livePipeline?.close() }
    }

    // 异步检索消费者：固定跑在 16MB 栈的推理线程池上，通过容量 1 的通道
    // 串行处理最新请求；结果只入队，不直接碰 Compose 状态。
    LaunchedEffect(Unit) {
        Log.i(TAG, "异步检索消费者协程已启动（主线程）")
        withContext(engine.inferenceDispatcher) {
            Log.i(TAG, "异步 1:N 检索消费者已切到推理线程")
            for (request in searchQueue) {
                Log.d(TAG, "开始异步检索 pid=${request.pid} version=${request.version}")
                val startedAt = SystemClock.elapsedRealtime()
                val outcome = runCatching {
                    engine.database.search(request.image, request.rect, request.landmarks)
                }
                val costMs = SystemClock.elapsedRealtime() - startedAt
                // 失败不能当作“未命中”回写，否则一次瞬时异常会把已登记的人
                // 错误标成“陌生人”；只把成功结果（含真正未命中）交给 UI 线程。
                outcome.fold(
                    onSuccess = { hit ->
                        Log.i(TAG, "异步 1:N 检索完成 pid=${request.pid} 耗时Ms=$costMs 命中=${hit?.index ?: -1L}")
                        searchResults.add(SearchResult(request.pid, request.version, hit, request.spoof))
                    },
                    onFailure = { e ->
                        Log.e(TAG, "异步 1:N 检索失败 pid=${request.pid} 耗时Ms=$costMs", e)
                    },
                )
            }
        }
    }

    fun cachedPortrait(index: Long): Bitmap? {
        portraitCache[index]?.let { return it }
        val file = engine.gallery.photo(index)
        val decoded = if (file.isFile) {
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        } else {
            null
        }
        if (decoded != null) portraitCache[index] = decoded
        return decoded
    }

    /**
     * 在相机线程消费异步检索结果。只接受当前版本；同一 pid 已提交新请求时，
     * 旧结果直接丢弃，避免“新脸显示旧身份”。
     */
    fun drainSearchResults() {
        while (true) {
            val result = searchResults.poll() ?: return
            if (recognizeVersion[result.pid] != result.version) continue
            val hit = result.hit
            identities[result.pid] = if (hit != null) {
                Log.d(TAG, "1:N 命中 pid=${result.pid} index=${hit.index} 相似度=${hit.similarity}")
                CamIdentity(
                    label = engine.gallery.name(hit.index),
                    similarity = hit.similarity,
                    spoof = result.spoof,
                    portrait = cachedPortrait(hit.index),
                )
            } else {
                CamIdentity("陌生人", null, result.spoof, null)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        CameraAnalysisPreview(
            initialFrontCamera = settings.frontCamera,
            cameraRotation = settings.cameraRotation,
            viewRotation = settings.viewRotation,
            mirrorFrontCamera = settings.mirrorFrontCamera,
            onFacingChange = onFacingChange,
            onStatus = { status = it },
            onMessage = onMessage,
            process = { frame ->
                // toUprightBitmap 已把帧缩放并摆正到分析分辨率;这里仅对
                // CameraX 异常交付的尺寸兜底。tracker 硬绑定构造分辨率,
                // native 对不匹配的帧直接 abort。
                val tDecode = SystemClock.elapsedRealtime()
                val frameNo = frameCounter.incrementAndGet()
                val normalized = if (frame.width != ANALYSIS_W || frame.height != ANALYSIS_H) {
                    frame.scale(ANALYSIS_W, ANALYSIS_H)
                } else {
                    frame
                }
                // 整帧只做一次 Bitmap→BGR 转换，跟踪/活体/识别共享同一块
                // native 缓冲（JNI 每次调用都会把像素拷走，覆写安全）。
                val native = frameBuffer.convert(normalized)
                val live = livePipeline
                val tTrack0 = SystemClock.elapsedRealtime()
                // 隔帧跟踪：缓存为空（画面里还没人）时每帧都检测，保证新面孔
                // 尽快出现；有框后每 TRACK_EVERY_N_FRAMES 帧更新一次，中间帧
                // 复用上一帧的框，避免 30ms 的检测把预览拖到 18fps。
                val cachedTracked = trackCache.get()
                val tracked = if (
                    frameNo % TRACK_EVERY_N_FRAMES == 0L || cachedTracked.isEmpty()
                ) {
                    tracker.track(native).also { trackCache.set(it) }
                } else {
                    cachedTracked
                }
                val tTrack1 = SystemClock.elapsedRealtime()
                // 先把上一轮异步检索结果落到 identities，再画当前帧标注。
                drainSearchResults()
                val now = SystemClock.elapsedRealtime()
                if (tracked.isEmpty()) liveSpoof.clear()
                var liveMs = 0L
                var searchMs = 0L
                for (face in tracked) {
                    // 视频活体按隔帧融合跑；静默模式逐帧只做跟踪，活体留在识别帧。
                    val analyzed = if (videoMode) {
                        live?.takeIf { frameNo % LIVENESS_EVERY_N_FRAMES == 0L }?.let {
                            synchronized(engine.nativeLock) { it.analyze(native, face.rect) }
                        }
                    } else {
                        null
                    }
                    analyzed?.let { liveMs += SystemClock.elapsedRealtime() - tTrack1 }
                    analyzed?.spoof?.let { liveSpoof[face.pid] = it }
                    val last = lastRecognizedAt[face.pid] ?: 0L
                    val due = now - last >= RECOGNIZE_INTERVAL_MS || !identities.containsKey(face.pid)
                    if (due && now - lastFullRunAt.get() >= RECOGNIZE_GLOBAL_GAP_MS) {
                        lastRecognizedAt[face.pid] = now
                        lastFullRunAt.set(now)
                        val tFallback0 = SystemClock.elapsedRealtime()
                        // 首选本帧视频活体的产出；静默模式或未轮到融合帧时用
                        // 精简流水线单帧补算；都没有才退回共享全量流水线。
                        val fallback = analyzed ?: live?.let {
                            synchronized(engine.nativeLock) { it.analyze(native, face.rect) }
                        } ?: synchronized(engine.nativeLock) {
                            engine.pipeline.analyze(native, face.rect)
                        }
                        // 静默模式的单帧活体仍在相机线程上，计入 live 而不是 search，
                        // 否则会把异步检索的收益算丢。
                        if (analyzed == null) {
                            liveMs += SystemClock.elapsedRealtime() - tFallback0
                        }
                        val landmarks = fallback?.landmarks
                        if (fallback != null && landmarks != null) {
                            // 异步检索：image 必须拷贝，native 缓冲下一帧就会被覆写。
                            // version 用于丢弃同一 pid 的旧结果。
                            val tSubmit0 = SystemClock.elapsedRealtime()
                            val version = (recognizeVersion[face.pid] ?: 0L) + 1L
                            recognizeVersion[face.pid] = version
                            val imageCopy = SeetaImageData(
                                native.width,
                                native.height,
                                native.channels,
                            ).also { native.data.copyInto(it.data) }
                            val request = SearchRequest(
                                pid = face.pid,
                                version = version,
                                image = imageCopy,
                                rect = Rect(face.rect),
                                landmarks = landmarks,
                                spoof = fallback.spoof,
                            )
                            val accepted = searchQueue.trySend(request).isSuccess
                            searchMs += SystemClock.elapsedRealtime() - tSubmit0
                            Log.d(TAG, "提交异步检索 pid=${face.pid} version=$version 队列接受=$accepted")
                            if (!accepted) {
                                Log.d(TAG, "异步检索队列未接收 pid=${face.pid}，本帧跳过")
                            }
                        } else {
                            identities[face.pid] = CamIdentity("无关键点", null, analyzed?.spoof, null)
                        }
                    }
                }
                val tAnnotate0 = SystemClock.elapsedRealtime()
                // 新的一段视频序列不能继承上一个人的活体帧，人消失即重置。
                if (live != null) {
                    if (tracked.isEmpty()) {
                        if (hadFaces.getAndSet(false)) {
                            synchronized(engine.nativeLock) { live.resetVideo() }
                            Log.d(TAG, "画面中已无人脸，重置视频活体序列")
                        }
                    } else {
                        hadFaces.set(true)
                    }
                }
                // 状态行与活体同频刷新（隔帧），避免每帧重组状态文本。
                if (frameNo % LIVENESS_EVERY_N_FRAMES == 0L) {
                    val scoreLine = live?.spoofScores()
                        ?.let { (clarity, reality) -> " · 活体分 ${"%.2f/%.2f".format(clarity, reality)}" }
                        ?: ""
                    status = "跟踪 ${tracked.size} 人 · 库中 ${engine.database.count} 张$scoreLine"
                }
                val annotated = annotateCameraFrame(normalized, tracked, identities, liveSpoof)
                profiler.sample(
                    faces = tracked.size,
                    largest = tracked.maxByOrNull { it.rect.width() },
                    decodeMs = tTrack0 - tDecode,
                    trackMs = tTrack1 - tTrack0,
                    liveMs = liveMs,
                    searchMs = searchMs,
                    annotateMs = SystemClock.elapsedRealtime() - tAnnotate0,
                )
                annotated
            },
        )
        if (settings.livenessMode == AppSettings.LIVENESS_VIDEO && livePipeline == null) {
            Text(
                "视频活体引擎准备中…(就绪前使用单帧活体)",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(status, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(top = 8.dp))
        if (identities.isNotEmpty()) {
            Text(
                identities.entries.sortedBy { it.key }.joinToString("\n") { (pid, id) ->
                    val parts = buildList {
                        add(id.label)
                        id.similarity?.let { add("相似度 ${"%.2f".format(it)}") }
                        id.spoof?.let { add(spoofLabel(it)) }
                    }
                    "PID $pid: ${parts.joinToString(" · ")}"
                },
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun spoofLabel(status: FaceAntiSpoofing.Status): String = when (status) {
    FaceAntiSpoofing.Status.REAL -> "真人"
    FaceAntiSpoofing.Status.SPOOF -> "假体"
    FaceAntiSpoofing.Status.FUZZY -> "无法判断"
    FaceAntiSpoofing.Status.DETECTING -> "检测中"
}

/**
 * 帧管线分段计时:每 [REPORT_EVERY] 帧向 logcat 输出各阶段均值与有脸帧占比,
 * 用于把"性能差"定位到具体阶段(decode=整帧 Bitmap→BGR,track=检测跟踪,
 * live=视频活体,submit=1:N 检索提交(整帧拷贝+入队),annotate=画框)。
 * 真正的 1:N 检索耗时见异步消费者打印的“异步 1:N 检索完成”日志。另统计"框位移"——
 * 同一人相邻帧跟踪框坐标的平均变化(px),度量画面静止时线框的抖动程度,
 * 用于验证 videoStable 帧间平滑的效果。
 */
private class FrameProfiler {
    private val total = LongArray(5)
    private var frames = 0
    private var framesWithFace = 0
    private var lastPid = -1
    private var lastRect: Rect? = null
    private var boxDeltaSum = 0f

    fun sample(
        faces: Int,
        largest: TrackedFace?,
        decodeMs: Long,
        trackMs: Long,
        liveMs: Long,
        searchMs: Long,
        annotateMs: Long,
    ) {
        total[0] += decodeMs; total[1] += trackMs; total[2] += liveMs
        total[3] += searchMs; total[4] += annotateMs
        frames++
        if (faces > 0) framesWithFace++
        // 只对同一人的相邻帧计位移;换人/丢脸直接重置基准。
        if (largest != null) {
            val last = lastRect
            if (last != null && lastPid == largest.pid) {
                val r = largest.rect
                boxDeltaSum += (
                    kotlin.math.abs(r.left - last.left) + kotlin.math.abs(r.top - last.top) +
                        kotlin.math.abs(r.right - last.right) + kotlin.math.abs(r.bottom - last.bottom)
                    ) / 4f
            }
            lastPid = largest.pid
            lastRect = Rect(largest.rect)
        } else {
            lastPid = -1
            lastRect = null
        }
        if (frames % REPORT_EVERY == 0) {
            val f = frames.toFloat()
            Log.i(
                TAG,
                "帧耗时分段(近 $REPORT_EVERY 帧均值 ms): " +
                    "decode=${"%.1f".format(total[0] / f)} " +
                    "track=${"%.1f".format(total[1] / f)} " +
                    "live=${"%.1f".format(total[2] / f)} " +
                    "submit=${"%.1f".format(total[3] / f)} " +
                    "annotate=${"%.1f".format(total[4] / f)} " +
                    "有脸帧占比=${(framesWithFace * 100f / frames).toInt()}% " +
                    "框位移=${"%.1f".format(boxDeltaSum / f)}px",
            )
            java.util.Arrays.fill(total, 0L)
            frames = 0
            framesWithFace = 0
            boxDeltaSum = 0f
        }
    }

    private companion object {
        const val REPORT_EVERY = 50
    }
}

private fun annotateCameraFrame(
    source: Bitmap,
    faces: List<TrackedFace>,
    identities: Map<Int, CamIdentity>,
    liveSpoof: Map<Int, FaceAntiSpoofing.Status>,
): Bitmap {
    val out = if (source.isMutable) source else source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(0, 229, 255)
        strokeWidth = 3f
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    val tagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
    }
    val portraitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    val portraitBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f
    }

    faces.forEach { face ->
        canvas.drawRect(face.rect, box)
        val identity = identities[face.pid] ?: return@forEach

        val chipTop = (face.rect.top - 54f).coerceAtLeast(4f)
        // 名字标签：名字 · 相似度 · 活体结论（活体用本帧的实时结论）。
        val parts = buildList {
            add(identity.label)
            identity.similarity?.let { add("%.2f".format(it)) }
            (liveSpoof[face.pid] ?: identity.spoof)?.let { add(spoofLabel(it)) }
        }
        val rawLabel = parts.joinToString(" · ")
        val thumbW = if (identity.portrait != null) 54f else 0f
        // 名牌整体夹在画面左右 4px 内:先量尺寸再回推左缘,避免贴边时
        // 人像/文字画出画面或文字跑出背景框。
        val maxChipWidth = out.width - 8f
        val maxTextWidth = (maxChipWidth - thumbW - 16f).coerceAtLeast(40f)
        val label = ellipsize(rawLabel, text, maxTextWidth)
        val textWidth = text.measureText(label)
        val chipWidth = thumbW + textWidth + 16f
        val chipLeft = face.rect.left.toFloat()
            .coerceIn(4f, (out.width - 4f - chipWidth).coerceAtLeast(4f))

        // 人像小图：贴在名牌最左侧。
        identity.portrait?.let { thumb ->
            val chipSize = 48f
            val scale = chipSize / maxOf(thumb.width, thumb.height)
            val w = thumb.width * scale
            val h = thumb.height * scale
            val rect = RectF(chipLeft, chipTop, chipLeft + w, chipTop + h)
            canvas.drawBitmap(thumb, null, rect, portraitPaint)
            canvas.drawRect(rect, portraitBorder)
        }
        val textLeft = chipLeft + thumbW
        val tagRect = RectF(textLeft, chipTop, textLeft + textWidth + 16f, chipTop + 40f)
        canvas.drawRoundRect(tagRect, 8f, 8f, tagBg)
        canvas.drawText(label, textLeft + 8f, chipTop + 29f, text)
    }
    return out
}

/** 按像素宽度截断文字并补省略号,保证名牌文字不超出背景框。 */
private fun ellipsize(label: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(label) <= maxWidth) return label
    val dots = "…"
    val budget = maxWidth - paint.measureText(dots)
    if (budget <= 0f) return dots
    var end = 0
    while (end < label.length && paint.measureText(label, 0, end + 1) <= budget) end++
    return label.substring(0, end) + dots
}
