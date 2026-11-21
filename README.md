# FaceForAndroid — SeetaFace6 Android 人脸识别

本仓库包含两部分：

- **`face/sf`（face-sf）**：把开源人脸识别引擎 [SeetaFace6](https://github.com/SeetaFace6Open/index)
  与官方 JNI 绑定 [SeetaFace6JNI](https://github.com/SeetaFace6Open/SeetaFace6JNI)
  打包成开箱即用的 Android Library/AAR。上游代码以 git submodule 原样引入、不打补丁，
  所有 NDK/CMake 构建适配都在模块内完成，细节见 [face/sf/README.md](face/sf/README.md)。
- **`facemirror`（面鉴）**：基于 face-sf 的演示应用，miuix 风格 UI，三个 Tab 覆盖静态
  检测分析、1:1/1:N 身份比对与实时相机识别。GitHub Release 提供内置模型的 APK 直装包。

## 功能一览

- 人脸检测 / 视频流跟踪 / 5 点与 68 点关键点
- 1:1 比对与 1:N 人脸库（注册 / 搜索 / 持久化，每个识别模型独立库）
- 单帧静默活体与多帧视频活体（可调现实分阈值）
- 质量评估：清晰度、亮度、完整性、姿态、分辨率、光噪/模糊（LBN）
- 属性：年龄、性别、眼睛状态、口罩
- 实时相机（CameraX）：检测框 + 姓名牌 + 活体徽标 + videoStable 抗抖，帧耗时分段统计

## 模块结构

```
facemirror/            面鉴 App（Kotlin + Compose/miuix）
face/sf/               face-sf Android 库（AAR，含 JNI/CMake 编排）
face/sf/third_party/   SeetaFace6 / SeetaFace6JNI 等 submodule（统一指向个人 fork）
```

## 构建要求

- JDK 17（AGP 9.4.1 / Gradle 9.9.1 / Kotlin 2.2.10 / compileSdk 37）
- NDK `27.0.12077973` + CMake `3.22.1`（已在 `face/sf/build.gradle.kts` 钉选）
- 首次拉取：`git submodule update --init --recursive`

```bash
git submodule update --init --recursive
./gradlew :facemirror:assembleDebug
```

## 模型文件

SeetaFace6 的 `.csta` 模型（数百 MB 二进制）不进版本库。获取方式见
[face/sf/README.md](face/sf/README.md)。支持两种放置方式：

- **目录**：`facemirror/src/main/assets/seeta/` 下直接放 `.csta` 文件，首次启动逐个复制；
- **压缩包**：放一个 `facemirror/src/main/assets/seeta.7z`，首次启动自动解压到应用私有目录。

应用启动时会自动探测 assets 里是目录还是 7z，并在初始化界面显示当前阶段、进度和耗时；
已安装且压缩包未变化时直接复用，不会重复解压。7z 建议只使用 LZMA2 压缩，不要加密或使用
BCJ2 过滤器。GitHub Release 的 APK 已内置模型，可直接安装体验。

## 隐私与安全约定

人脸特征值、人脸图片等生物特征属敏感个人信息：本工程约定这类数据**一律不进日志、
不上传**，人脸库仅保存在设备本地（`database.save()` 到应用私有目录）。

## 第三方组件

SeetaFace6、SeetaFace6JNI、SeetaFaceTutorial 等均以 git submodule 引入，统一指向
本仓库维护者的 GitHub fork（`LiJoeAllen/*`），便于国内网络稳定拉取。fork 的代码内容与
上游对应 commit 一致，仅 SeetaFace6 fork 的 `.gitmodules` 调整为指向各算法模块的 fork；
各组件许可以其上游仓库为准。
