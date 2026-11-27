// UiBits.kt —— UI 公共件：图片解码/裁剪/标注等位图工具，以及各 Tab 共用的
// 卡片、徽标、相似度判定、质量条、确认弹窗等小组件。

package cn.heavenlybook.facemirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.graphics.Rect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.heavenlybook.sf.face.SeetaFaceResult
import cn.heavenlybook.sf.face.SeetaQuality
import cn.heavenlybook.sf.face.SeetaQualityLevel
import cn.heavenlybook.sf.face.SeetaQualityOutcome
import com.seeta.sdk.FaceAntiSpoofing
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 把 content Uri 解码为 ARGB_8888 位图，长边不超过 [maxDim]（按 2 的幂采样降采样）。 */
internal fun decodeScaled(context: Context, uri: Uri, maxDim: Int = 1280): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = context.contentResolver.openInputStream(uri)!!.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: error("无法解码所选图片")
    return if (bitmap.isMutable) {
        bitmap
    } else {
        bitmap.copy(Bitmap.Config.ARGB_8888, true)
    }
}

/** 从 [source] 裁出 [rect] 区域（四周各外扩 25%），长边超过 [maxDim] 时等比缩小。 */
internal fun cropFaceBitmap(source: Bitmap, rect: Rect, maxDim: Int = 320): Bitmap {
    val mx = (rect.width() * 0.25f).toInt()
    val my = (rect.height() * 0.25f).toInt()
    val crop = Rect(
        (rect.left - mx).coerceAtLeast(0),
        (rect.top - my).coerceAtLeast(0),
        (rect.right + mx).coerceAtMost(source.width),
        (rect.bottom + my).coerceAtMost(source.height),
    )
    val cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
    val longSide = maxOf(cropped.width, cropped.height)
    return if (longSide <= maxDim) cropped
    else {
        val scale = maxDim.toFloat() / longSide
        Bitmap.createScaledBitmap(
            cropped,
            (cropped.width * scale).toInt().coerceAtLeast(1),
            (cropped.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}

/**
 * 在 [source] 的可变副本上直接绘制人脸框、序号和关键点，返回标注后的位图。
 * [densePoints]（可选，如主导人脸的 68 点集）以品红小点绘制，与 5 点关键点区分。
 */
internal fun annotate(
    source: Bitmap,
    faces: List<SeetaFaceResult>,
    densePoints: List<android.graphics.PointF>? = null,
): Bitmap {
    val out = if (source.isMutable) source else source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = android.graphics.Color.rgb(0, 229, 255)
        strokeWidth = maxOf(3f, source.width / 300f)
    }
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(0, 255, 128) }
    val denseDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(255, 0, 255) }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.YELLOW
        textSize = maxOf(28f, source.width / 24f)
        setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
    }
    faces.forEachIndexed { index, face ->
        canvas.drawRect(face.rect, box)
        canvas.drawText("#${index + 1}", face.rect.left.toFloat(), (face.rect.top - 6f).coerceAtLeast(30f), text)
        face.landmarks?.forEach { point ->
            canvas.drawCircle(point.x, point.y, box.strokeWidth, dot)
        }
    }
    densePoints?.forEach { point ->
        canvas.drawCircle(point.x, point.y, box.strokeWidth * 0.6f, denseDot)
    }
    return out
}

// ---------------------------------------------------------------------------
// 各 Tab 共用的小卡片/徽标构件
// ---------------------------------------------------------------------------

private data class SpoofStyle(val label: String, val container: Color, val content: Color)

private fun spoofStyle(status: FaceAntiSpoofing.Status?): SpoofStyle = when (status) {
    FaceAntiSpoofing.Status.REAL -> SpoofStyle("真人", Color(0xFFDCF5DC), Color(0xFF1B7A1B))
    FaceAntiSpoofing.Status.SPOOF -> SpoofStyle("假体", Color(0xFFF9DCDC), Color(0xFFB3261E))
    FaceAntiSpoofing.Status.FUZZY -> SpoofStyle("无法判断", Color(0xFFFBEBD3), Color(0xFF9A6700))
    FaceAntiSpoofing.Status.DETECTING -> SpoofStyle("检测中", Color(0xFFE4E4E4), Color(0xFF555555))
    null -> SpoofStyle("未启用", Color(0xFFE4E4E4), Color(0xFF777777))
}

/** 活体判定徽标：真人/假体/无法判断等结论配对应底色。 */
@Composable
internal fun SpoofBadge(status: FaceAntiSpoofing.Status?) {
    val style = spoofStyle(status)
    Surface(color = style.container, contentColor = style.content, shape = RoundedCornerShape(6.dp)) {
        Text(
            style.label,
            style = MiuixTheme.textStyles.footnote2,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** 中性属性徽标（性别 / 年龄 / 口罩 / 眼睛状态…）。 */
@Composable
internal fun InfoChip(text: String) {
    // 库默认 onSecondaryContainer 是 #A9A9A9 灰字配 #F0F0F0 灰底(对比仅 2:1),
    // 改用 onSecondaryVariant(#303030/#D9D9D9),深浅色都 >9:1。
    Surface(
        color = MiuixTheme.colorScheme.secondaryContainer,
        contentColor = MiuixTheme.colorScheme.onSecondaryVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text,
            style = MiuixTheme.textStyles.footnote2,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * 1:1 式判定视图：大号相似度数字、达标/不达标配色、进度条，并附判定阈值作参照。
 */
@Composable
internal fun SimilarityVerdict(similarity: Float, threshold: Float) {
    val pass = similarity >= threshold
    val color = if (pass) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "%.3f".format(similarity),
                style = MiuixTheme.textStyles.headline2,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(
                if (pass) "同一人" else "不同人",
                style = MiuixTheme.textStyles.title4,
                color = color,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        LinearProgressIndicator(
            progress = similarity.coerceIn(0f, 1f),
            colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = color),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "判定阈值 ${"%.2f".format(threshold)}",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private val qualityLabels = mapOf(
    SeetaQuality.Item.BRIGHTNESS to "亮度",
    SeetaQuality.Item.CLARITY to "清晰度",
    SeetaQuality.Item.INTEGRITY to "完整性",
    SeetaQuality.Item.POSE to "姿态",
    SeetaQuality.Item.POSE_EX to "深姿态",
    SeetaQuality.Item.RESOLUTION to "分辨率",
    SeetaQuality.Item.LIGHT_BLUR_NOISE to "光噪",
)

private fun qualityLevelColor(level: SeetaQualityLevel): Color = when (level) {
    SeetaQualityLevel.HIGH -> Color(0xFF1B7A1B)
    SeetaQualityLevel.MEDIUM -> Color(0xFF9A6700)
    SeetaQualityLevel.LOW -> Color(0xFFB3261E)
}

/** 每个质量项一根带标签的进度条；光噪（LBN）没有分数，改显示其结论档位。 */
@Composable
internal fun QualityBars(outcomes: Map<SeetaQuality.Item, SeetaQualityOutcome>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        outcomes.forEach { (item, outcome) ->
            if (item == SeetaQuality.Item.LIGHT_BLUR_NOISE) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "光噪",
                        style = MiuixTheme.textStyles.footnote1,
                        modifier = Modifier.fillMaxWidth(0.22f),
                    )
                    Text(
                        "模糊度 ${outcome.level}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = qualityLevelColor(outcome.level),
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        qualityLabels[item] ?: item.name,
                        style = MiuixTheme.textStyles.footnote1,
                        modifier = Modifier.fillMaxWidth(0.22f),
                    )
                    LinearProgressIndicator(
                        progress = outcome.score.coerceIn(0f, 1f),
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            foregroundColor = qualityLevelColor(outcome.level),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${outcome.level} ${"%.2f".format(outcome.score)}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

/** 危险操作（删除等）的二次确认弹窗；[show] 为 false 时不显示。 */
@Composable
internal fun ConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmLabel: String = "删除",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = title,
        summary = message,
        onDismissRequest = onDismiss,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            ) { Text(confirmLabel) }
        }
    }
}
