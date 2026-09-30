import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cn.heavenlybook.facemirror"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cn.heavenlybook.facemirror"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// ---------------------------------------------------------------------------
// Gitea Release 发布:构建 debug APK 并作为附件挂到仓库的版本发布页
// (https://gitea.heavenlybook.cn/JoeAllen/FaceForAndroid/releases)。
// 用法:./gradlew :facemirror:publishApkToGiteaRelease
//  - tag 取 versionName(v1.0),已存在则复用该 Release 并替换同名附件
//  - release 构建无签名配置,不可直接安装,故发布 debug 包
//  - 凭证:seeta.maven.user/seeta.maven.token 属性或 GITEA_USER/GITEA_TOKEN
//    环境变量(与 :face:sf 的 Maven 发布共用,不入仓库)
// ---------------------------------------------------------------------------
val giteaReleaseUser = providers.gradleProperty("seeta.maven.user")
    .orElse(providers.environmentVariable("GITEA_USER"))
val giteaReleaseToken = providers.gradleProperty("seeta.maven.token")
    .orElse(providers.environmentVariable("GITEA_TOKEN"))
val appVersionName = android.defaultConfig.versionName

tasks.register("publishApkToGiteaRelease") {
    group = "publishing"
    description = "Builds the debug APK and attaches it to the Gitea Release (tag = v<versionName>)."
    dependsOn("assembleDebug")

    // 配置缓存要求:doLast 闭包只能捕获可序列化值,git 推导/凭证全部在配置期完成。
    val apkPath = layout.projectDirectory
        .file("build/outputs/apk/debug/facemirror-debug.apk").asFile.absolutePath
    val remote = providers.exec {
        commandLine("git", "remote", "get-url", "origin")
    }.standardOutput.asText.get().trim().removeSuffix(".git")
    val m = Regex("""^(https?://[^/]+)/([^/]+)/([^/]+)$""").find(remote)
        ?: error("无法从 git remote 推导 Gitea 地址: $remote")
    val (host, owner, repo) = m.destructured
    val user = giteaReleaseUser.orNull
    val token = giteaReleaseToken.orNull
    val auth = Base64.getEncoder().encodeToString("$user:$token".toByteArray())
    val api = "$host/api/v1/repos/$owner/$repo"
    val tag = "v$appVersionName"
    val ver = appVersionName
    val assetName = "facemirror-$ver-debug.apk"
    val releasePageUrl = "$host/$owner/$repo/releases/tag/$tag"

    doLast {
        val apk = File(apkPath)
        require(apk.exists()) { "APK 不存在,请确认 assembleDebug 已执行: $apk" }
        require(!user.isNullOrBlank() && !token.isNullOrBlank()) {
            "缺少 Gitea 凭证:请在 ~/.gradle/gradle.properties 配置 seeta.maven.user/seeta.maven.token"
        }

        fun http(
            url: String,
            method: String,
            body: ByteArray? = null,
            contentType: String? = null,
        ): Pair<Int, ByteArray> {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Basic $auth")
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val bytes = stream?.readBytes() ?: ByteArray(0)
            conn.disconnect()
            return code to bytes
        }

        // Release 已存在则复用,否则基于 tag 创建(Gitea 会自动建 tag)。
        val (code, resp) = http("$api/releases/tags/$tag", "GET")
        val releaseText: String
        val releaseId = when (code) {
            200 -> {
                releaseText = String(resp)
                Regex(""""id"\s*:\s*(\d+)""").find(releaseText)?.groupValues?.get(1)?.toLong()
                    ?: error("解析 Release id 失败")
            }
            404 -> {
                val (createCode, createResp) = http(
                    "$api/releases", "POST",
                    body = """{"tag_name":"$tag","name":"$tag","body":"facemirror v$ver 自动发布"}"""
                        .toByteArray(),
                    contentType = "application/json",
                )
                require(createCode in 200..299) {
                    "创建 Release 失败($createCode): ${String(createResp)}"
                }
                releaseText = String(createResp)
                Regex(""""id"\s*:\s*(\d+)""").find(releaseText)?.groupValues?.get(1)?.toLong()
                    ?: error("解析新建 Release id 失败")
            }
            else -> error("查询 Release 失败($code): ${String(resp)}")
        }

        // 同名附件先删后传,保证重复发布幂等(Gitea attachment 的 id 在 name 前)。
        Regex(""""id"\s*:\s*(\d+)[^{}]*?"name"\s*:\s*"$assetName"""")
            .findAll(releaseText)
            .forEach { old ->
                val oldId = old.groupValues[1].toLong()
                val (delCode, delResp) = http("$api/releases/$releaseId/assets/$oldId", "DELETE")
                println("已删除旧附件 $assetName (id=$oldId): $delCode ${String(delResp).take(120)}")
            }

        val (uploadCode, uploadResp) = http(
            "$api/releases/$releaseId/assets?name=$assetName",
            "POST",
            body = apk.readBytes(),
            contentType = "application/octet-stream",
        )
        require(uploadCode in 200..299) {
            "上传附件失败($uploadCode): ${String(uploadResp).take(300)}"
        }
        println("已发布 $assetName (${apk.length() / 1024 / 1024} MB) -> $releasePageUrl")
    }
}

dependencies {
    implementation(project(":face:sf"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.miuix.ui)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}