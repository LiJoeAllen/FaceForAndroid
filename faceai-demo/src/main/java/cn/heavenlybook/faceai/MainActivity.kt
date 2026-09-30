// MainActivity.kt —— app-t 主界面：FaceAISDK 各能力的入口聚合
// （1:1 验证 / 1:N 搜索 / 活体 / 静态比对 / 设置）。

package cn.heavenlybook.faceai

import android.content.Intent
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.faceAI.demo.FaceAISettingsActivity
import com.faceAI.demo.FaceSDKConfig
import com.faceAI.demo.SysCamera.addFace.AddFaceFeatureActivity
import com.faceAI.demo.SysCamera.search.FaceSearch1NActivity
import com.faceAI.demo.SysCamera.search.FaceSearchDataMangerActivity
import com.faceAI.demo.SysCamera.verify.FaceVerificationActivity
import com.faceAI.demo.SysCamera.verify.LivenessDetectActivity
import com.faceAI.demo.SysCamera.verify.TwoFaceImageVerifyActivity
import kotlinx.coroutines.launch

private const val TAG = "FaceAiMain"

/** 唯一 Activity：直接渲染 [FaceHomeScreen] 能力入口列表。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                FaceHomeScreen()
            }
        }
    }
}

/** 首页：FaceID 输入 + 三张能力卡片；SDK 结果经 startActivityForResult 回传后弹 snackbar。 */
@Composable
fun FaceHomeScreen() {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var faceId by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun toast(msg: String) = scope.launch { snackbarHostState.showSnackbar(msg) }

    // 1:1 验证结果：code/faceID/message/similarity/livenessValue
    val verifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        // faceID 可能是手机号等实名标识，不进日志；分数与 message 无身份含义，可记。
        Log.d(TAG, "验证能力返回 code=${data.getIntExtra("code", -1)}")
        toast(
            "验证结束: ${data.getStringExtra("message")}" +
                "  相似度=${data.getFloatExtra("similarity", 0f)}" +
                "  活体分=${data.getFloatExtra("livenessValue", 0f)}"
        )
    }

    // 人脸录入结果：code/faceID/message/faceFeature
    val addFaceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val code = data.getIntExtra("code", 0)
        Log.d(TAG, "人脸录入返回 code=$code")
        toast(
            if (code == 1) "录入成功: ${data.getStringExtra("faceID")}"
            else "录入取消: ${data.getStringExtra("message")}"
        )
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = "清空人脸库",
            message = "将删除本机全部 1:N 人脸特征和缓存图片，确定继续？",
            onConfirm = {
                showClearConfirm = false
                Log.i(TAG, "清空本机 1:N 人脸库与缓存图片")
                FaceSDKConfig.clearAllFaceSearchData(context)
                toast("人脸库已清空")
            },
            onDismiss = { showClearConfirm = false }
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("FaceAI 人脸识别", style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = faceId,
                onValueChange = { faceId = it.trim() },
                label = { Text("FaceID（用户唯一标识，如手机号）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("1:1 人脸验证（刷脸比对 + 活体检测）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "先用 FaceID 录入底片，再发起验证。数据保存在本机 MMKV。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                addFaceLauncher.launch(
                                    Intent(context,AddFaceFeatureActivity::class.java)
                                        .putExtra(AddFaceFeatureActivity.ADD_FACE_IMAGE_TYPE_KEY, "FACE_VERIFY")
                                        .putExtra(FaceVerificationActivity.USER_FACE_ID_KEY, faceId)
                                )
                            },
                            enabled = faceId.isNotBlank()
                        ) { Text("录入底片") }
                        Button(
                            onClick = {
                                verifyLauncher.launch(
                                    Intent(context,FaceVerificationActivity::class.java)
                                        .putExtra(FaceVerificationActivity.USER_FACE_ID_KEY, faceId)
                                )
                            },
                            enabled = faceId.isNotBlank()
                        ) { Text("开始验证") }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("1:N 人脸搜索（人脸库识别）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "向人脸库录入多张人脸后，对镜头即可搜索出最相似的人。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                addFaceLauncher.launch(
                                    Intent(context,AddFaceFeatureActivity::class.java)
                                        .putExtra(AddFaceFeatureActivity.ADD_FACE_IMAGE_TYPE_KEY, "FACE_SEARCH")
                                        .putExtra(FaceVerificationActivity.USER_FACE_ID_KEY, faceId)
                                )
                            }
                        ) { Text("录入人脸") }
                        Button(
                            onClick = {
                                verifyLauncher.launch(
                                    Intent(context,FaceSearch1NActivity::class.java)
                                )
                            }
                        ) { Text("开始搜索") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            verifyLauncher.launch(
                                Intent(context,FaceSearchDataMangerActivity::class.java)
                            )
                        }) { Text("人脸库管理") }
                        OutlinedButton(onClick = { showClearConfirm = true }) { Text("清空人脸库") }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("更多能力", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            verifyLauncher.launch(
                                Intent(context,LivenessDetectActivity::class.java)
                            )
                        }) { Text("活体检测") }
                        OutlinedButton(onClick = {
                            verifyLauncher.launch(
                                Intent(context,TwoFaceImageVerifyActivity::class.java)
                            )
                        }) { Text("静态图比对") }
                        OutlinedButton(onClick = {
                            verifyLauncher.launch(
                                Intent(context,FaceAISettingsActivity::class.java)
                            )
                        }) { Text("SDK 设置") }
                    }
                }
            }
        }
    }
}

/** 危险操作（清空人脸库等）的二次确认弹窗。 */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(message)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = onConfirm) { Text("确定") }
                }
            }
        }
    }
}
