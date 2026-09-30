// App.kt —— faceai-demo 入口 Application：承接 FaceAISDK 的全局初始化。

package cn.heavenlybook.faceai

import android.util.Log
import com.faceAI.demo.FaceApplication

/**
 * FaceAISDK 要求在 Application onCreate 中完成初始化:
 * MMKV(1:1 人脸特征存储)、语音提示、人脸数据目录等。
 */
class App : FaceApplication() {

    override fun onCreate() {
        super.onCreate()
        Log.d("FaceAiApp", "Application 初始化完成（FaceAISDK 全局上下文已就绪）")
    }
}
