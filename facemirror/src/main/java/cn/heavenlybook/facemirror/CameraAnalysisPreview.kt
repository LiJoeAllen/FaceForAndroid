// CameraAnalysisPreview.kt —— 可复用的实时相机画面组件：权限申请、CameraX 分析流、
// 前置镜像、逐帧门控与 FPS 角标都在这里，各相机功能只提供一个 process 帧回调。

package cn.heavenlybook.facemirror

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// 分析分辨率取 360x480：相比旧的 480x640，检测/关键点/活体的单帧开销降到
// 约 56%，同时仍支持 60px 的最小人脸。
internal const val ANALYSIS_W = 360
internal const val ANALYSIS_H = 480

private const val TAG = "CameraAnalysisPreview"

/**
 * 所有相机功能共用的实时相机画面组件。
 *
 * 相机相关杂务全部收在这里：权限门、CameraX ImageAnalysis（360x480、
 * KEEP_ONLY_LATEST，跑在 16 MB 栈的分析线程上 —— TenniS/OpenMP 的 Debug 帧
 * 会撑爆约 1 MB 的 Java 线程栈）、严格顺序的资源释放、前后摄切换（前置画面
 * 做镜像以与预览一致），以及采集/渲染 FPS 角标 —— 采集统计 CameraX 送达的
 * 帧数，渲染统计完整处理并显示的帧数，掉帧可定位到采集侧还是推理侧。
 *
 * [process] 在分析线程上执行，拿到的是摆正后（前置已镜像）的 ARGB 帧，
 * 返回标注好的位图用于显示。帧做了门控：上一帧还在处理时，新到的帧直接丢弃。
 */
@Composable
internal fun CameraAnalysisPreview(
    modifier: Modifier = Modifier,
    initialFrontCamera: Boolean,
    onFacingChange: (Boolean) -> Unit,
    process: (Bitmap) -> Bitmap,
    onStatus: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    var frontCamera by remember { mutableStateOf(initialFrontCamera) }
    var annotated by remember { mutableStateOf<Bitmap?>(null) }
    var fpsText by remember { mutableStateOf<String?>(null) }
    val captured = remember { AtomicLong(0) }
    val rendered = remember { AtomicLong(0) }

    // 跨重组共享；onDispose 时按严格顺序拆除，保证分析线程绝不会碰到
    // 已被调用方关闭的跟踪器。
    val cameraState = remember {
        val stackSize = 16L * 1024 * 1024
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(null, runnable, "seeta-camera-analyzer", stackSize).apply { isDaemon = true }
        }
        object {
            val provider = AtomicReference<ProcessCameraProvider?>()
            val closed = AtomicBoolean(false)
            val busy = AtomicBoolean(false)
            val executor = executor
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "相机组件销毁：停止分析流并释放线程池")
            cameraState.closed.set(true)
            cameraState.provider.get()?.unbindAll()
            cameraState.executor.shutdown()
            try {
                // 最多等 3 秒让在途帧处理完，避免 native 推理被中途掐断。
                cameraState.executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    LaunchedEffect(Unit) {
        var lastCaptured = 0L
        var lastRendered = 0L
        while (true) {
            delay(1000)
            val c = captured.get()
            val r = rendered.get()
            fpsText = "采集 ${c - lastCaptured} · 渲染 ${r - lastRendered} fps"
            lastCaptured = c
            lastRendered = r
        }
    }

    if (!hasPermission) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(ANALYSIS_W.toFloat() / ANALYSIS_H)
                .padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("实时人脸识别需要相机权限", style = MiuixTheme.textStyles.main)
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("授权相机")
            }
        }
        return
    }

    LaunchedEffect(hasPermission, frontCamera) {
        val provider = context.cameraProvider()
        cameraState.provider.set(provider)
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            Size(ANALYSIS_W, ANALYSIS_H),
                            androidx.camera.core.resolutionselector.ResolutionStrategy
                                .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(cameraState.executor) { proxy ->
            captured.incrementAndGet()
            if (cameraState.closed.get() || !cameraState.busy.compareAndSet(false, true)) {
                proxy.close()
                return@setAnalyzer
            }
            try {
                if (cameraState.closed.get()) return@setAnalyzer
                // 旋转与镜像合并成一次矩阵变换：前置帧做水平镜像，让送入推理的
                // 画面与用户看到的预览一致；标注和识别都在这张图上进行。
                // 缩放并入同一次绘制，产出恰好是分析分辨率，下游不再二次缩放。
                val bitmap = proxy.toUprightBitmap(
                    mirror = frontCamera,
                    targetWidth = ANALYSIS_W,
                    targetHeight = ANALYSIS_H,
                )
                annotated = process(bitmap)
                rendered.incrementAndGet()
            } catch (e: Exception) {
                Log.e(TAG, "帧处理异常 facing=${if (frontCamera) "front" else "back"}", e)
                onStatus("帧处理失败:${e.message}")
            } finally {
                proxy.close()
                cameraState.busy.set(false)
            }
        }

        try {
            provider.unbindAll()
            val selector = if (frontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            provider.bindToLifecycle(lifecycleOwner, selector, analysis)
            Log.i(TAG, "相机已启动 ${if (frontCamera) "前置" else "后置"} ${ANALYSIS_W}x$ANALYSIS_H")
            onStatus("相机运行中")
        } catch (e: Exception) {
            // 多数机型只有一个镜头能搭配 ImageAnalysis；把失败呈现出来让用户
            // 切回另一个镜头，而不是让整个取景循环崩掉。
            Log.e(TAG, "相机启动失败 front=$frontCamera", e)
            onStatus("相机启动失败:${e.message}")
            onMessage("该摄像头不可用,已保持原画面")
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ANALYSIS_W.toFloat() / ANALYSIS_H),
    ) {
        annotated?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Text(
            "相机预热中…",
            modifier = Modifier.align(Alignment.Center),
        )
        fpsText?.let {
            Surface(
                color = Color.Black.copy(alpha = 0.45f),
                contentColor = Color.White,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Text(
                    it,
                    style = MiuixTheme.textStyles.footnote2,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        // 叠在预览上的翻转按钮：半透明圆角胶囊，不依赖主题按钮配色。
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .clickable { frontCamera = !frontCamera; onFacingChange(frontCamera) },
        ) {
            Text(
                "翻转摄像头",
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 把 [ImageProxy] 一步解码为摆正（可同时镜像、缩放到目标尺寸）的 ARGB 位图：
 * 旋转与镜像共用同一个 [Matrix]，缩放通过画布变换并入同一次绘制 —— 每帧只
 * 产生一次全图像素拷贝。[targetWidth]/[targetHeight] 为 0 时保持摆正后的
 * 原始尺寸。
 */
internal fun ImageProxy.toUprightBitmap(
    mirror: Boolean,
    targetWidth: Int = 0,
    targetHeight: Int = 0,
): Bitmap {
    val source = toBitmap()
    val matrix = Matrix()
    val rotation = imageInfo.rotationDegrees
    if (rotation != 0) matrix.postRotate(rotation.toFloat())
    if (mirror) matrix.postScale(-1f, 1f, source.width / 2f, source.height / 2f)
    if (targetWidth <= 0 || targetHeight <= 0) {
        if (matrix.isIdentity) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
    val vertical = rotation == 90 || rotation == 270
    val uprightWidth = if (vertical) source.height else source.width
    val uprightHeight = if (vertical) source.width else source.height
    if (matrix.isIdentity && uprightWidth == targetWidth && uprightHeight == targetHeight) {
        return source
    }
    // 直接画到目标尺寸的画布上，一次绘制完成旋转/镜像/缩放。旋转矩阵会把
    // 内容转到负坐标区（createBitmap(matrix) 会自动平移，画布不会），所以
    // 先用 mapRect 求变换后边界，再平移+缩放让内容恰好铺满画布。
    val srcBounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
    val fitted = RectF()
    matrix.mapRect(fitted, srcBounds)
    val scaled = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(scaled)
    val fitX = targetWidth.toFloat() / fitted.width()
    val fitY = targetHeight.toFloat() / fitted.height()
    canvas.translate(-fitted.left * fitX, -fitted.top * fitY)
    canvas.scale(fitX, fitY)
    canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    return scaled
}

/** 挂起获取 [ProcessCameraProvider]：把回调式的 Future 转成协程等待。 */
internal suspend fun Context.cameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (t: Throwable) {
                    continuation.resumeWithException(t)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }
