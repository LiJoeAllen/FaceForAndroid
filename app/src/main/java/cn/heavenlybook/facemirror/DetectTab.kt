// DetectTab.kt —— 检测分析 Tab：选一张图片做全能力检测（检测/属性/活体/质量），
// 逐人脸出结果卡片。

package cn.heavenlybook.facemirror

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.heavenlybook.sf.face.SeetaFaceResult
import cn.heavenlybook.sf.face.SeetaQualityOutcome
import com.seeta.sdk.FaceAntiSpoofing
import com.seeta.sdk.GenderPredictor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一张已分析完的人脸，字段均已转成可直接上卡片显示的文本。 */
private data class FaceUi(
    val portrait: Bitmap,
    val sizeText: String,
    val gender: String,
    val age: String,
    val mask: String,
    val eyes: String,
    val spoof: FaceAntiSpoofing.Status?,
    val quality: Map<cn.heavenlybook.sf.face.SeetaQuality.Item, SeetaQualityOutcome>?,
    val lbn: cn.heavenlybook.sf.face.LbnStates?,
)

/** 一次图片分析的完整结果：标注后的整图 + 每张脸的卡片数据。 */
private data class AnalysisUi(
    val annotated: Bitmap,
    val faces: List<FaceUi>,
)

private const val TAG = "DetectTab"

/** Tab 1「检测分析」：对所选图片做全能力检测，逐人脸出结果卡片。 */
@Composable
internal fun DetectTab(engine: FaceSdk, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var analysis by remember { mutableStateOf<AnalysisUi?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch(engine.inferenceDispatcher) {
                try {
                    // 推理跑在 16MB 栈的专用线程池（见 FaceSdk.inferenceDispatcher），
                    // 默认 IO/主线程栈会在 TenniS 内核里溢出。
                    val started = SystemClock.elapsedRealtime()
                    val result = analyzeImage(engine, decodeScaled(context, uri))
                    Log.i(
                        TAG,
                        "图片分析完成 faces=${result.faces.size} 耗时Ms=${SystemClock.elapsedRealtime() - started}",
                    )
                    withContext(Dispatchers.Main) { analysis = result }
                } catch (e: Exception) {
                    Log.e(TAG, "图片分析失败", e)
                    withContext(Dispatchers.Main) { onMessage("分析失败：${e.message}") }
                } finally {
                    busy = false
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Button(
                onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = !busy,
            ) {
                Text(if (busy) "分析中…" else "选择图片并分析")
            }
        }
        if (busy) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(size = 20.dp)
                    Text("检测与属性分析中…", style = MiuixTheme.textStyles.footnote1)
                }
            }
        }
        val result = analysis
        if (result == null && !busy) {
            item { EmptyHint("选择一张含人脸的图片开始分析") }
        }
        result?.let { r ->
            item {
                Image(
                    bitmap = r.annotated.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    "共检测到 ${r.faces.size} 张人脸",
                    style = MiuixTheme.textStyles.title4,
                )
            }
            items(r.faces) { face -> FaceCard(face) }
            val (hasPoseEx, hasLbn) = engine.advancedQuality
            if (!hasPoseEx || !hasLbn) {
                item {
                    Text(
                        "深度质量部分未启用,缺少模型:" + listOf(
                            if (!hasPoseEx) "pose_estimation.csta" else null,
                            if (!hasLbn) "quality_lbn.csta + face_landmarker_pts68.csta" else null,
                        ).joinToString("、"),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

/**
 * 跑检测 + 所有已启用组件；质量评估只算最大（主导）人脸 —— 全部脸都算
 * 质量开销翻倍而展示价值有限。
 */
private fun analyzeImage(engine: FaceSdk, source: Bitmap): AnalysisUi {
    val faces = synchronized(engine.nativeLock) { engine.pipeline.detect(source) }
    val sorted = faces.sortedByDescending { it.rect.width() }
    val dominant = sorted.firstOrNull()
    val dominantQuality = dominant?.landmarks?.let {
        synchronized(engine.nativeLock) { engine.quality.check(source, dominant.rect, it) }
    }
    val dominantLbn = if (engine.advancedQuality.second) {
        synchronized(engine.nativeLock) { engine.quality.checkLightBlurNoise(source) }
    } else {
        null
    }
    // 68 点密集关键点只标注主导人脸 —— 管线内的组件必须吃 5 点模型，
    // 68 点由独立的 SeetaFaceLandmarker 供应，纯用于可视化。
    val dominantDensePoints = if (dominant != null) {
        engine.landmarker68?.let {
            synchronized(engine.nativeLock) { it.mark(source, dominant.rect) }
        }
    } else {
        null
    }
    val cards = sorted.map { face ->
        FaceUi(
            portrait = cropFaceBitmap(source, face.rect, maxDim = 256),
            sizeText = "${face.rect.width()}x${face.rect.height()}px",
            gender = when (face.gender) {
                GenderPredictor.GENDER.MALE -> "男"
                GenderPredictor.GENDER.FEMALE -> "女"
                null -> "性别 -"
            },
            age = face.age?.let { "$it 岁" } ?: "年龄 -",
            mask = face.mask?.let { if (it.masked) "口罩 有" else "口罩 无" } ?: "口罩 -",
            eyes = face.eyes?.let {
                "眼 ${eyeText(it.left)}/${eyeText(it.right)}"
            } ?: "眼 -",
            spoof = face.spoof,
            quality = if (face == dominant) dominantQuality else null,
            lbn = if (face == dominant) dominantLbn else null,
        )
    }
    return AnalysisUi(annotate(source, faces, dominantDensePoints), cards)
}

private fun eyeText(state: com.seeta.sdk.EyeStateDetector.EYE_STATE): String = when (state) {
    com.seeta.sdk.EyeStateDetector.EYE_STATE.EYE_OPEN -> "睁"
    com.seeta.sdk.EyeStateDetector.EYE_STATE.EYE_CLOSE -> "闭"
    else -> "?"
}

private fun lightLabel(state: com.seeta.sdk.QualityOfLBN.LIGHTSTATE): String = when (state) {
    com.seeta.sdk.QualityOfLBN.LIGHTSTATE.BRIGHT -> "偏亮"
    com.seeta.sdk.QualityOfLBN.LIGHTSTATE.DARK -> "偏暗"
}

private fun blurLabel(state: com.seeta.sdk.QualityOfLBN.BLURSTATE): String = when (state) {
    com.seeta.sdk.QualityOfLBN.BLURSTATE.CLEAR -> "清晰"
    com.seeta.sdk.QualityOfLBN.BLURSTATE.BLUR -> "模糊"
}

private fun noiseLabel(state: com.seeta.sdk.QualityOfLBN.NOISESTATE): String = when (state) {
    com.seeta.sdk.QualityOfLBN.NOISESTATE.HAVENOISE -> "有噪"
    com.seeta.sdk.QualityOfLBN.NOISESTATE.NONOISE -> "无噪"
}

/** 单张人脸的结果卡片：人像缩略图 + 属性徽标 + （主导脸的）质量条。 */
@Composable
private fun FaceCard(face: FaceUi) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    bitmap = face.portrait.asImageBitmap(),
                    contentDescription = "人脸裁剪",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(face.sizeText, style = MiuixTheme.textStyles.title4)
                        SpoofBadge(face.spoof)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoChip(face.gender)
                        InfoChip(face.age)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoChip(face.mask)
                        InfoChip(face.eyes)
                    }
                }
            }
            face.quality?.let { quality ->
                if (quality.isNotEmpty()) {
                    Text("质量评估", style = MiuixTheme.textStyles.footnote1)
                    face.lbn?.let { lbn ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            InfoChip("光 ${lightLabel(lbn.light)}")
                            InfoChip("模糊 ${blurLabel(lbn.blur)}")
                            InfoChip("噪 ${noiseLabel(lbn.noise)}")
                        }
                    }
                    QualityBars(quality)
                }
            }
        }
    }
}

/** 列表空态占位文案。 */
@Composable
internal fun EmptyHint(message: String) {
    Text(
        message,
        style = MiuixTheme.textStyles.main,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    )
}
