// FaceDemoApp.kt —— 应用主壳：miuix 顶栏（就绪徽标 + 设置入口）、底部导航
// 三个能力 Tab；所有面向用户的消息统一走一个 snackbar。

package cn.heavenlybook.facemirror

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
import cn.heavenlybook.sf.face.SeetaFace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
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
import java.io.IOException

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
    var engineError by remember { mutableStateOf<String?>(null) }
    var initializing by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(AppSettings.load(context)) }
    var showSettings by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun onMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    DisposableEffect(Unit) {
        onDispose { engine?.close() }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                Log.i(
                    TAG,
                    "开始初始化引擎 model=${settings.recognizerModel} " +
                        "minFace=${settings.minFaceSize} threads=${settings.inferThreads}",
                )
                val modelsDir = SeetaFace.installModels(context)
                engine = FaceSdk(
                    modelsDir,
                    context.filesDir,
                    settings.minFaceSize,
                    settings.inferThreads,
                    settings.recognizerModel,
                    settings.livenessRealityThreshold,
                )
                Log.i(TAG, "引擎初始化完成，进入工作台")
            } catch (e: Exception) {
                Log.e(TAG, "引擎初始化失败", e)
                engineError = when (e) {
                    is IOException -> "未找到模型文件:${e.message}"
                    else -> e.message ?: "初始化失败"
                }
            } finally {
                initializing = false
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "面鉴",
                actions = {
                    if (!initializing && engineError == null) {
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
                initializing -> EngineStateCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(size = 20.dp)
                        Text("正在初始化 SeetaFace6 引擎…", style = MiuixTheme.textStyles.main)
                    }
                }
                engineError != null -> EngineStateCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("引擎初始化失败:$engineError", style = MiuixTheme.textStyles.main)
                        Text(
                            "请确认已将 SeetaFace6 的 .csta 模型文件放入 assets/seeta/(清单见工程 README)。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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

/** 引擎状态卡片：初始化中/失败两种占位状态共用。 */
@Composable
private fun EngineStateCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp), insideMargin = PaddingValues(16.dp)) {
        content()
    }
}

private const val TAG = "FaceDemoApp"
