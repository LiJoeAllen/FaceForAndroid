// MainActivity.kt —— 应用唯一 Activity：仅承载 Compose 内容的容器。

package cn.heavenlybook.facemirror

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.heavenlybook.facemirror.ui.theme.FaceMirrorTheme

/** 应用入口 Activity：套主题后渲染 [FaceDemoApp]，业务全在 Compose 层。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate")
        enableEdgeToEdge()
        setContent {
            // 主题内的 miuix Scaffold 自行处理状态栏/导航栏内边距，不再外包一层。
            FaceMirrorTheme {
                FaceDemoApp()
            }
        }
    }
}
