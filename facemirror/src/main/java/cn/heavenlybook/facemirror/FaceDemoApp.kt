// FaceDemoApp.kt —— 应用主壳：miuix 顶栏（就绪徽标 + 设置入口）、底部导航
// 三个能力 Tab；所有面向用户的消息统一走一个 snackbar。

package cn.heavenlybook.facemirror

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.heavenlybook.facemirror.ui.AnalyzeIcon
import cn.heavenlybook.facemirror.ui.CameraIcon
import cn.heavenlybook.facemirror.ui.IdentityIcon
import cn.heavenlybook.sf.face.ModelInstallProgress
import cn.heavenlybook.sf.face.ModelInstallStage
import cn.heavenlybook.sf.face.SeetaFace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 产品主壳：顶栏（就绪徽标 + 设置入口）、底部导航和三个能力 Tab；
 * 所有面向用户的消息统一汇聚到同一个 snackbar 展示。
 */
@Composable
fun FaceDemoApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var engine by remember { mutableStateOf<FaceSdk?>(null) }
    var settings by remember { mutableStateOf(AppSettings.load(context)) }
    var showSettings by remember { mutableStateOf(false) }
    var initAttempt by remember { mutableIntStateOf(0) }
    val initState = remember { MutableStateFlow(EngineInitState()) }
    val init by initState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    fun onMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    DisposableEffect(Unit) {
        onDispose { engine?.close() }
    }

    LaunchedEffect(initAttempt) {
        val startedAt = SystemClock.elapsedRealtime()
        val runtime = Runtime.getRuntime()
        fun elapsedMs() = SystemClock.elapsedRealtime() - startedAt

        fun publish(
            phase: InitPhase = InitPhase.RUNNING,
            stage: String,
            detail: String = "",
            progress: Float? = null,
            source: String = initState.value.source,
            error: String? = null,
        ) {
            initState.value = EngineInitState(
                phase = phase,
                stage = stage,
                detail = detail,
                progress = progress,
                elapsedMs = elapsedMs(),
                source = source,
                error = error,
            )
        }

        // 初始化监控：每 2s 打一条心跳；30s 无进展时升级为 warn，便于真机抓日志定位卡点。
        val monitor = launch {
            var lastKey = ""
            var lastChangeAt = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(2_000)
                initState.update { it.copy(elapsedMs = elapsedMs()) }
                val state = initState.value
                if (state.phase != InitPhase.RUNNING) break
                val key = "${state.stage}|${state.progress}|${state.detail}"
                val now = SystemClock.elapsedRealtime()
                if (key != lastKey) {
                    lastKey = key
                    lastChangeAt = now
                }
                val stalledMs = now - lastChangeAt
                val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                val heapMaxMb = runtime.maxMemory() / 1024 / 1024
                val line = "初始化监控 阶段=${state.stage} 进度=${state.progress?.let { "%.0f%%".format(it * 100) } ?: "不确定"} " +
                    "已用=${state.elapsedMs / 1000}s 停滞=${stalledMs / 1000}s 堆=${heapUsedMb}/${heapMaxMb}MB"
                if (stalledMs >= 30_000) Log.w(TAG, "$line（超过 30s 无进展）") else Log.i(TAG, line)
            }
        }

        try {
            withContext(Dispatchers.IO) {
                Log.i(
                    TAG,
                    "开始初始化引擎 model=${settings.recognizerModel} " +
                        "minFace=${settings.minFaceSize} threads=${settings.inferThreads}",
                )
                val source = SeetaFace.detectModelSource(context)
                publish(stage = "检测模型来源", detail = source.displayName, source = source.displayName)
                Log.i(TAG, "模型来源 ${source.displayName}")

                var lastProgressAt = 0L
                val modelsDir = SeetaFace.installModels(context) { progress ->
                    val now = SystemClock.elapsedRealtime()
                    val shouldPublish = progress.stage == ModelInstallStage.READY ||
                        now - lastProgressAt >= 150 ||
                        (progress.entryCount > 0 && progress.entryIndex >= progress.entryCount)
                    if (shouldPublish) {
                        lastProgressAt = now
                        publish(
                            stage = progress.message,
                            detail = if (progress.stage == ModelInstallStage.READY) "模型安装完成" else formatInstallDetail(progress),
                            progress = if (progress.stage == ModelInstallStage.READY) 1f else progress.fraction,
                            source = source.displayName,
                        )
                    }
                }

                publish(
                    stage = "加载 SeetaFace6 原生引擎",
                    detail = "模型已安装（${SeetaFace.countModels(modelsDir)} 个 .csta），正在初始化 native 组件…",
                    progress = null,
                    source = source.displayName,
                )
                Log.i(TAG, "模型安装完成 dir=${modelsDir.path} 开始构建 FaceSdk")
                val sdk = FaceSdk(
                    modelsDir,
                    context.filesDir,
                    settings.minFaceSize,
                    settings.inferThreads,
                    settings.recognizerModel,
                    settings.livenessRealityThreshold,
                )
                withContext(Dispatchers.Main) {
                    engine?.close()
                    engine = sdk
                }
            }
            publish(
                phase = InitPhase.READY,
                stage = "引擎已就绪",
                detail = "初始化完成",
                progress = 1f,
            )
            Log.i(TAG, "引擎初始化完成，进入工作台 总耗时=${elapsedMs()}ms")
        } catch (e: Exception) {
            Log.e(TAG, "引擎初始化失败 耗时=${elapsedMs()}ms", e)
            val message = when (e) {
                is java.io.IOException -> e.message ?: "模型读取失败"
                else -> e.message ?: e.javaClass.simpleName
            }
            publish(
                phase = InitPhase.FAILED,
                stage = "初始化失败",
                detail = "",
                progress = null,
                error = message,
            )
        } finally {
            monitor.cancel()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "面鉴",
                actions = {
                    if (init.phase == InitPhase.READY) {
                        // 中性灰底深字:蓝底白字对比不足(浅模式仅 2.8:1)。
                        Surface(
                            color = MiuixTheme.colorScheme.secondaryContainer,
                            contentColor = MiuixTheme.colorScheme.onSecondaryVariant,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                "引擎就绪",
                                style = MiuixTheme.textStyles.footnote2,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    TextButton(text = "设置", onClick = { showSettings = true })
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = AnalyzeIcon,
                    label = "检测分析",
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = IdentityIcon,
                    label = "身份比对",
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = CameraIcon,
                    label = "实时相机",
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val currentEngine = engine
            when {
                init.phase == InitPhase.RUNNING -> EngineStateCard {
                    InitProgressContent(init)
                }
                init.phase == InitPhase.FAILED -> EngineStateCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "引擎初始化失败：${init.error ?: "未知错误"}",
                            style = MiuixTheme.textStyles.main,
                            color = MiuixTheme.colorScheme.error,
                        )
                        Text(
                            "支持两种模型放置方式：assets/seeta/ 下直接放 .csta 文件，" +
                                "或放一个 assets/seeta.7z 压缩包（首次启动自动解压）。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (init.source.isNotBlank()) {
                            Text(
                                "模型来源：${init.source}",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        if (init.elapsedMs > 0) {
                            Text(
                                "失败前已用时 ${formatElapsed(init.elapsedMs)}",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        TextButton(
                            text = "重试",
                            onClick = {
                                engine?.close()
                                engine = null
                                initState.value = EngineInitState(stage = "重新初始化…")
                                initAttempt++
                            },
                        )
                    }
                }
                currentEngine != null -> when (tab) {
                    0 -> DetectTab(currentEngine, { onMessage(it) })
                    1 -> IdentityTab(currentEngine, settings, { onMessage(it) })
                    else -> CameraTab(
                        engine = currentEngine,
                        settings = settings,
                        onFacingChange = { front ->
                            settings = settings.copy(frontCamera = front)
                            settings.save(context)
                        },
                        onMessage = { onMessage(it) },
                    )
                }
            }
        }
    }

    SettingsSheet(
        show = showSettings,
        settings = settings,
        onChange = {
            it.save(context)
            settings = it
        },
        onDismiss = { showSettings = false },
    )
}

/** 初始化阶段：驱动顶栏徽标和主内容区的占位状态。 */
private enum class InitPhase { RUNNING, READY, FAILED }

/** 初始化 UI 状态快照；由 IO 线程写入、Compose 侧 collectAsState 读取。 */
private data class EngineInitState(
    val phase: InitPhase = InitPhase.RUNNING,
    val stage: String = "准备初始化…",
    val detail: String = "",
    val progress: Float? = null,
    val elapsedMs: Long = 0,
    val source: String = "",
    val error: String? = null,
)

/** 初始化进度卡片：阶段、进度条、详细字节数和已用时。 */
@Composable
private fun InitProgressContent(state: EngineInitState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(size = 20.dp)
            Text(state.stage, style = MiuixTheme.textStyles.main)
        }
        val progress = state.progress
        if (progress != null) {
            LinearProgressIndicator(
                progress = progress.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${(progress * 100).toInt()}%",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (state.detail.isNotBlank()) {
            Text(
                state.detail,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Text(
            buildString {
                append("已用时 ${formatElapsed(state.elapsedMs)}")
                if (state.source.isNotBlank()) append(" · ${state.source}")
            },
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 把安装进度转成一行可读文本；总量未知时只显示已完成量。 */
private fun formatInstallDetail(progress: ModelInstallProgress): String {
    val parts = buildList {
        if (progress.entryCount > 0) add("${progress.entryIndex}/${progress.entryCount} 个文件")
        if (progress.bytesTotal > 0) {
            add("${formatBytes(progress.bytesDone)} / ${formatBytes(progress.bytesTotal)}")
        } else if (progress.bytesDone > 0) {
            add(formatBytes(progress.bytesDone))
        }
    }
    return if (parts.isEmpty()) progress.message else parts.joinToString(" · ")
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000.0
    return if (seconds < 60) "%.1fs".format(seconds)
    else "%dm%02ds".format(ms / 60_000, (ms / 1_000) % 60)
}

/** 引擎状态卡片：初始化中/失败两种占位状态共用。 */
@Composable
private fun EngineStateCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp), insideMargin = PaddingValues(16.dp)) {
        content()
    }
}

private const val TAG = "FaceDemoApp"
