// Theme.kt —— 应用主题：miuix（MIUI 风格）深浅色跟随系统。

package cn.heavenlybook.facemirror.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 应用主题：miuix 组件库的深浅色配色，随系统切换。 */
@Composable
fun FaceMirrorTheme(content: @Composable () -> Unit) {
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
