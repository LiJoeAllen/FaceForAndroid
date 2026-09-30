plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cn.heavenlybook.faceai"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cn.heavenlybook.faceai"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // FaceAISDK 只提供这两种 ABI 的 so 库
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

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

dependencies {
    // FaceAISDK 官方 Demo 库(SDK AAR 以 api 方式传递依赖)
    implementation(project(":face:FaceAISDK_Android:FaceSDKLib"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// 对齐 CameraX 版本(兼容 Android 16KB Page)
val cameraVersion = "1.5.2"
configurations.configureEach {
    resolutionStrategy {
        force(
            "androidx.camera:camera-core:$cameraVersion",
            "androidx.camera:camera-camera2:$cameraVersion",
            "androidx.camera:camera-lifecycle:$cameraVersion",
            "androidx.camera:camera-view:$cameraVersion"
        )
    }
}