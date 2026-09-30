// SettingsSheet.kt —— 设置底部弹层：阈值/相机/检测参数等全部可调项，
// 只负责收集变更，持久化与引擎生效时机见 [AppSettings]。

package cn.heavenlybook.facemirror

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/**
 * 产品设置弹层：1:1 / 1:N 相似度阈值、默认前后摄、最小可检人脸、识别模型、
 * 视频活体帧数等。[onChange] 回传更新后的值；持久化和引擎接线由调用方负责。
 */
@Composable
internal fun SettingsSheet(
    show: Boolean,
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    WindowBottomSheet(
        show = show,
        title = "设置",
        // 默认抽屉底 background 与卡片 surfaceContainer 在浅色模式下都是纯白,
        // 分组卡片会"消失";改用 surface(浅 F7F7F7/深黑)让白卡片浮出来。
        backgroundColor = MiuixTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // 抽屉内容会延伸到导航栏区域,底部留出系统手势条 + 间距,
                // 否则滚到底时最后一项会被手势条压住。
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 默认 SmallTitle 用 onBackgroundVariant(对比仅 2.8),在 F7 底上偏弱,
            // 提到 onSurfaceVariantSummary(对比 5.7)保证分组标题清晰可读。
            SmallTitle(text = "比对与检索", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThresholdSlider(
                        label = "1:1 比对阈值",
                        hint = "相似度达到该值判定为同一人",
                        value = settings.verifyThreshold,
                        onChange = { onChange(settings.copy(verifyThreshold = it)) },
                    )
                    ThresholdSlider(
                        label = "1:N 检索阈值",
                        hint = "高于 1:1 阈值可减少误命中",
                        value = settings.searchThreshold,
                        onChange = { onChange(settings.copy(searchThreshold = it)) },
                    )
                }
            }

            SmallTitle(text = "相机", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingSwitchRow(
                        title = "前置摄像头",
                        summary = "关闭时使用后置摄像头",
                        checked = settings.frontCamera,
                        onChecked = { onChange(settings.copy(frontCamera = it)) },
                    )
                    ThresholdSlider(
                        label = "最小可检人脸 (px)",
                        hint = "调大更快,太小的人脸会被忽略;重启应用后生效",
                        value = settings.minFaceSize.toFloat(),
                        valueRange = 40f..160f,
                        steps = 11,
                        format = { "%d".format(it.toInt()) },
                        onChange = { onChange(settings.copy(minFaceSize = it.toInt())) },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("相机活体模式", style = MiuixTheme.textStyles.body1)
                        TabRow(
                            tabs = listOf("静默(快)", "视频(稳)"),
                            selectedTabIndex = if (settings.livenessMode == AppSettings.LIVENESS_VIDEO) 1 else 0,
                            // 默认选中段底色 surfaceContainer 与卡片底色相同会融掉,
                            // 这里选中段用主色 + 反白字,在任何底色上都可辨。
                            colors = TabRowDefaults.tabRowColors(
                                backgroundColor = MiuixTheme.colorScheme.surface,
                                contentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                selectedBackgroundColor = MiuixTheme.colorScheme.primary,
                                selectedContentColor = MiuixTheme.colorScheme.onPrimary,
                            ),
                            onTabSelected = { index ->
                                onChange(
                                    settings.copy(
                                        livenessMode = if (index == 1) {
                                            AppSettings.LIVENESS_VIDEO
                                        } else {
                                            AppSettings.LIVENESS_SILENT
                                        },
                                    ),
                                )
                            },
                        )
                        Text(
                            "静默=单帧判定,帧率高;视频=多帧融合更抗攻击,单次约 190ms;下次打开实时相机生效",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    ThresholdSlider(
                        label = "视频活体帧数",
                        hint = "相机活体融合的帧数,越大越稳越慢;下次打开实时相机生效",
                        value = settings.videoFrameCount.toFloat(),
                        valueRange = 3f..10f,
                        steps = 6,
                        format = { "%d".format(it.toInt()) },
                        onChange = { onChange(settings.copy(videoFrameCount = it.toInt())) },
                    )
                    ThresholdSlider(
                        label = "活体判定阈值",
                        hint = "现实分达到该值判真人;调高更严(易判假体),调低更松;重启应用后生效",
                        value = settings.livenessRealityThreshold,
                        valueRange = 0.50f..0.95f,
                        steps = 8,
                        onChange = { onChange(settings.copy(livenessRealityThreshold = it)) },
                    )
                    SettingSwitchRow(
                        title = "跟踪框防抖",
                        summary = "videoStable:帧间平滑跟踪框,压掉原始检测的逐帧抖动;响应稍慢;默认开启,下次打开实时相机生效",
                        checked = settings.videoStable,
                        onChecked = { onChange(settings.copy(videoStable = it)) },
                    )
                }
            }

            SmallTitle(text = "识别", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("识别模型", style = MiuixTheme.textStyles.body1)
                    AppSettings.RECOGNIZER_MODELS.forEach { (model, label) ->
                        ModelRow(
                            label = label,
                            selected = settings.recognizerModel == model,
                            onClick = { onChange(settings.copy(recognizerModel = model)) },
                        )
                    }
                    Text(
                        "各模型的底库相互独立、互不覆盖;切换后重启应用生效",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    ThresholdSlider(
                        label = "推理线程数",
                        hint = "0 为系统默认;影响底库比对吞吐,重启应用后生效",
                        value = settings.inferThreads.toFloat(),
                        valueRange = 0f..8f,
                        steps = 7,
                        format = { if (it.toInt() == 0) "自动" else "${it.toInt()}" },
                        onChange = { onChange(settings.copy(inferThreads = it.toInt())) },
                    )
                }
            }
        }
    }
}

/** 带标签、当前值和说明文字的通用滑杆行。 */
@Composable
private fun ThresholdSlider(
    label: String,
    hint: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0.40f..0.95f,
    steps: Int = 0,
    format: (Float) -> String = { "%.2f".format(it) },
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
            Text(format(value), style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
        )
        Text(
            hint,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 标题 + 说明 + 开关的整行设置项。 */
@Composable
private fun SettingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1)
            Text(
                summary,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** 识别模型单选行：选中项右侧打勾。 */
@Composable
private fun ModelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(
            label,
            style = MiuixTheme.textStyles.body1,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                "✓",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}
