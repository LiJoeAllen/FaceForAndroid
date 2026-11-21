# face-sf — SeetaFace6 Android 第三方发布库

`:face:sf` 把开源人脸识别引擎 [SeetaFace6](https://github.com/SeetaFace6Open/index)
和官方 JNI 绑定 [SeetaFace6JNI](https://github.com/SeetaFace6Open/SeetaFace6JNI)
打包成一个可直接依赖的 Android Library/AAR，按"可长期维护的优秀第三方库"标准组织：
上游代码以 git submodule 引入并统一指向本仓库维护者的 fork，代码本身不打补丁，
所有构建适配都在本模块内完成。

- **Maven 坐标**：`cn.heavenlybook.sf:face-sf:<version>`（当前 `6.0.0-alpha02`，见 `gradle/libs.versions.toml` 的 `seetaFace`）
- **Java API 包名**：`com.seeta.sdk`（沿用上游，JNI 符号 `Java_com_seeta_sdk_*` 依赖此包名，**不可更改**）
- **minSdk 24 / compileSdk 37**，支持 ABI：`arm64-v8a`、`armeabi-v7a`、`x86_64`
- 能力：人脸检测/跟踪、关键点、识别（1:1 与 FaceDatabase）、活体检测、姿态、质量评估、年龄/性别/眼睛状态/口罩检测

## 目录结构

```
face/sf/
  build.gradle.kts                 # AAR + NDK/CMake + maven-publish
  consumer-rules.pro               # 随 AAR 下发的 R8 规则
  src/main/AndroidManifest.xml     # 纯库，无 Activity/权限/资源
  src/main/kotlin/cn/heavenlybook/sf/face/
    SeetaFace.kt                   # 模型安装（assets -> 内部存储）
    SeetaImages.kt                 # Bitmap -> BGR SeetaImageData
    SeetaFacePipeline.kt           # 一站式管线：检测/关键点/识别/活体/属性
    SeetaFaceTracker.kt            # 视频流跟踪（人名 id）
    SeetaFaceDatabase.kt           # 1:N 人脸库（注册/搜索/持久化）
    SeetaQuality.kt                # 质量评估组合器（7 项）
  src/main/cpp/
    CMakeLists.txt                 # 原生构建总编排
    cmake/SeetaModules.cmake       # seeta_add_feature()/seeta_add_jni() 函数
    cmake/seeta_compat.h           # 现代 NDK 的标准头兼容（强制包含）
  third_party/                     # 全部为 git submodule，统一指向维护者 fork
    SeetaFace6/      -> github.com/LiJoeAllen/SeetaFace6.git（伞形仓库，fork 自 SeetaFace6Open/index）
      OpenRoleZoo/ SeetaAuthorize/ TenniS/ FaceBoxes/ ... 14 个 fork 后的算法子模块
    SeetaFace6JNI/   -> github.com/LiJoeAllen/SeetaFace6JNI.git
    SeetaFace6Docs/  -> github.com/LiJoeAllen/SeetaFace6Docs.git（文档与示例，fork 自 seetafaceengine/SeetaFace6）
    SeetaFaceTutorial/ -> github.com/LiJoeAllen/SeetaFaceTutorial.git（入门教程）
```

根 `.gitmodules` 登记四个直接子模块；SeetaFace6 fork 的 `.gitmodules` 将 14 个算法
子模块的 URL 一并改到对应 fork。各算法子模块的钉选 commit 与上游一致；SeetaFace6
因修改 `.gitmodules` 多出一个 fork 提交，其代码内容仍与上游 `a32e2fa` 一致。

## 当前钉选版本

| 仓库 | commit |
|---|---|
| SeetaFace6 (index) | `bd0fef5c1c922371bcffa5055cf5c5a1b89d47b8`（基于上游 `a32e2fa`，仅改 `.gitmodules`） |
| └ FaceBoxes | `6e806443a2f4dfbd9b9c446a03bc33250ee254b7` |
| └ FaceRecognizer6 | `c3bd558700e35e7a93437d55ebe342e83579ce6a` |
| └ FaceAntiSpoofingX6 | `f64b6c2faad79314a659e1e9d066b84938768d35` |
| └ FaceTracker6 | `41a327918ae64fa56aefe779b439b014584051a1` |
| └ Landmarker | `decacad713f6dc39c65ba0b4700988dc12bd6b7a` |
| └ PoseEstimator6 | `5201fa7ddceacb7ab34fdbc52509fd37bf570dda` |
| └ QualityAssessor3 | `9e21b10b2a2af381bd7673ecd758b151f1118acf` |
| └ SeetaAgePredictor | `71f3656c706da9685faa8b4fcdf8d74664edc413` |
| └ SeetaEyeStateDetector | `37ffcc0d1babd760f7dae0aef70a8c1f73c39a81` |
| └ SeetaGenderPredictor | `7850aa169e5d0dc031e8a3313f20076fb2114a0c` |
| └ SeetaMaskDetector | `79a782cb5595de3697a7da2f2eaab99a4bc40dea` |
| └ OpenRoleZoo | `38a4d6cd5d29248f03cea9bf04769093972107c9` |
| └ SeetaAuthorize | `add8911fb4ec0913df9eccf510e3f4266f6e18ff` |
| └ TenniS | `ef6c8332809a021d0eb5842c0f9d32a7f0b07f96` |
| SeetaFace6JNI | `1739a81a4e6a449343e33f2fc697ffc093ba2a53` |
| SeetaFace6Docs（seetafaceengine/SeetaFace6，文档+示例） | `0723e12cee1e7809a0bd29d0900e2794621e3c9f` |
| SeetaFaceTutorial（官方入门教程） | `a58b879ca5cc190f98b437e3f229988916125adb` |

用 `git submodule status --recursive` 可随时核对；升级后请同步更新本表。

## AAR 内容

每个 ABI 打包 24 个 SeetaFace 自有 `.so`：

- 基础：`libtennis.so`、`libSeetaAuthorize.so`（ORZ 为静态链接，不单独出现）
- 11 个功能库：`libSeetaFaceDetector600.so`、`libSeetaFaceRecognizer610.so`、
  `libSeetaFaceAntiSpoofingX600.so`、`libSeetaFaceTracking600.so`、
  `libSeetaFaceLandmarker600.so`、`libSeetaPoseEstimation600.so`、
  `libSeetaQualityAssessor300.so`、`libSeetaAgePredictor600.so`、
  `libSeetaEyeStateDetector200.so`、`libSeetaGenderPredictor600.so`、
  `libSeetaMaskDetector200.so`
- 11 个 JNI 包装库（名字严格对应 Java 端 `System.loadLibrary`，
  含上游两个历史拼写：`libFaceAntiSpoofing600_java.so`、`libSeetaMaksDetector200_java.so`）
- 运行时：`libc++_shared.so`；ARM 两个 ABI 额外带 NDK 的 `libomp.so`（TenniS 用 OpenMP）

`classes.jar` 为去重合并后的 25 个 `com.seeta.sdk` Java 类（上游每个模块目录都带一份
SeetaImageData/SeetaModelSetting 等公共类副本；合并任务按 `package` 声明归位，
同名内容不一致会直接构建失败）。

## 构建要求

- JDK **17** 运行 Gradle（已在 `gradle/gradle-daemon-jvm.properties` 钉选；
  JDK 25 的 `jlink` 无法处理 AGP 的 JdkImageTransform）
- NDK `27.0.12077973`、CMake `3.22.1`（在 `build.gradle.kts` 中钉选，用 SDK Manager 安装）
- 首次拉取必须递归初始化子模块：
  `git submodule update --init --recursive`

```bash
./gradlew :face:sf:assembleDebug          # 三 ABI 全量
./gradlew :app:assembleDebug              # 宿主集成验证
./gradlew :face:sf:publishToMavenLocal    # 发布到本机 Maven 仓库
```

## 使用

### 方式一：Kotlin 门面（推荐，`cn.heavenlybook.sf.face` 包）

模型文件（`.csta`）**不随 AAR 分发**，需从上游官方渠道（SeetaFace6 index 仓库 README
中的百度网盘/Dropbox 链接）下载，放到宿主 App 的 **`app/src/main/assets/seeta/`**
目录（`seeta` 目录名可自定义，与 `installModels` 参数对应）。按启用能力放置：

```
app/src/main/assets/seeta/
  face_detector.csta            # 人脸检测 / 跟踪（必需）
  face_landmarker_pts5.csta     # 5 点关键点（识别/活体/属性的前置）
  face_recognizer.csta          # 特征提取（1024 维；另有 mask/light 变体）
  fas_first.csta                # 活体检测（局部）
  fas_second.csta               # 活体检测（全局）
  age_predictor.csta            # 年龄
  gender_predictor.csta         # 性别
  eye_state.csta                # 眼睛状态
  mask_detector.csta            # 口罩检测
  pose_estimation.csta          # 深度姿态质量评估
  quality_lbn.csta              # 光量/模糊/噪声评估
  face_landmarker_pts68.csta    # 68 点（quality_lbn 依赖）
```

App 代码（首次运行会把 assets 里的模型拷贝到内部存储，SeetaFace 只能按路径加载）：

```kotlin
import cn.heavenlybook.sf.face.SeetaFace
import cn.heavenlybook.sf.face.SeetaFacePipeline

val modelsDir = SeetaFace.installModels(context)          // assets/seeta -> filesDir/seeta
SeetaFacePipeline.Builder(modelsDir)
    .withDetector(minFaceSize = 80)                       // 越大越快；默认 40；maxImageWidth/Height 可限制大图
    .withLandmarker()                                     // 识别/活体/属性的前置依赖
    .withRecognizer()
    .withAntiSpoofing(videoFrameCount = 10)               // >0 走视频活体；0 单帧
    .withAge().withGender().withEyeState().withMask()     // 属性四件（可选）
    .build()
    .use { pipeline ->
        val faces = pipeline.detect(bitmap)               // 一次拿到 框+5点+特征+活体+年龄/性别/眼睛/口罩
        val largest = pipeline.detectLargest(bitmap)      // 最大的那张脸
        if (faces.size >= 2) {
            val same = pipeline.compare(
                faces[0].feature!!, faces[1].feature!!
            ) >= SeetaFacePipeline.RECOMMENDED_SIMILARITY_THRESHOLD   // 0.62，官方推荐
        }
    }                                                     // close() 统一释放 native 资源
```

要点：

- pipeline 实例**非线程安全**（上游约束），多线程请各自创建；`minFaceSize`
  尽量设大（官方建议，80 相比 20 有约 16 倍速度差）。
- 活体视频模式：`withAntiSpoofing(videoFrameCount = 10)` 后 `detect()` 内部走
  `PredictVideo`，帧数不足时返回 `DETECTING`；换视频/换人时调用
  `pipeline.resetVideo()`；`pipeline.spoofScores()` 可取上一帧
  clarity/reality 分数用于调阈值（官方默认阈值 `SetThreshold(0.3, 0.8)`）。
- 活体/属性检测都要求人脸距图像边缘留出余量、分辨率 ≥128×128（局部活体依赖
  人脸周边上下文）。

### 视频流跟踪：SeetaFaceTracker

```kotlin
import cn.heavenlybook.sf.face.SeetaFaceTracker

SeetaFaceTracker(modelsDir, videoWidth = 1280, videoHeight = 720).use { tracker ->
    tracker.minFaceSize = 80
    tracker.videoStable = true                            // 框平滑，视觉更稳
    for (frame in cameraFrames) {
        val faces = tracker.track(frame)                  // TrackedFace(pid, rect, score)
        // 同一 pid == 同一个人：每人只挑一帧送识别即可
    }
}                                                         // 换流请重建实例（JNI 未暴露 reset）
```

### 1:N 人脸库：SeetaFaceDatabase

```kotlin
import cn.heavenlybook.sf.face.SeetaFaceDatabase

SeetaFaceDatabase.Builder(modelsDir)
    .withDetector(minFaceSize = 80)
    .withRecognizer()                                     // 库内特征必须来自同一识别模型
    .build()
    .use { db ->
        val index = db.add(portraitBitmap) ?: error("no face")   // 注册底库
        val hit = db.search(sceneBitmap)                         // 最相似的一条（score 自行判断）
        val hits = db.searchAll(sceneBitmap, threshold = 0.62f)  // 1:N 建议阈值高于 1:1
        db.save(File(context.filesDir, "gallery.db"))            // 持久化 / load() 恢复
    }
```

### 质量评估：SeetaQuality

```kotlin
import cn.heavenlybook.sf.face.SeetaQuality

SeetaQuality.Builder(modelsDir)
    .withBrightness().withClarity().withIntegrity().withPose()
    .withResolution()
    .withPoseEx()                              // 需要 pose_estimation.csta
    .withLightBlurNoise()                      // 需要 quality_lbn.csta + face_landmarker_pts68.csta
    .build()
    .use { quality ->
        val outcomes = quality.check(frame)    // Map<Item, (level, score)>，最大脸
        val ok = outcomes.values.all { it.level != SeetaQualityLevel.LOW }
        val lbn = quality.checkLightBlurNoise(frame)   // 可选：光量/模糊/噪声原始状态
    }
```

所有阈值默认取上游推荐值；评估项可任意组合，全部 `!= LOW` 一般即可判定质量合格。

### 方式二：直接使用上游 Java API（`com.seeta.sdk` 包）

```java
import com.seeta.sdk.*;

SeetaModelSetting setting = new SeetaModelSetting(
        0, new String[]{ modelPath + "/face_detector.csta" }, SeetaDevice.SEETA_DEVICE_AUTO);
FaceDetector detector = new FaceDetector(setting);
try {
    SeetaRect[] faces = detector.Detect(imageData);
} finally {
    detector.dispose();
}
```

设备字符串构造函数（如 `new FaceRecognizer(model, "cpu", 0)`）由本库的
`ModelSetting_v2` 适配层支持（"auto"/"cpu"/"gpu"，大小写不敏感）。

## 发布到远程仓库

默认只有 `publishToMavenLocal`。配置远程只需在 `~/.gradle/gradle.properties`
（或命令行 `-P`）提供：

```properties
seeta.maven.url=https://...        # GitHub Packages / MavenCentral / 自建 Nexus
seeta.maven.user=<user>
seeta.maven.token=<token>
```

然后执行 `./gradlew :face:sf:publishSeetaReleasePublicationToSeetaReleaseRepository`。
POM 已带 name/description/BSD-2-Clause/SCM；正式上 MavenCentral 还需补充签名
（signing）与 Sonatype 账号。

## 升级上游

运行 `scripts/update-upstream.cmd`（或手动执行其中命令）：更新两个子模块并递归同步，
随后：

1. `git submodule status --recursive` 记录新 commit，更新本 README 表格；
2. 全量 `assembleDebug` 三 ABI；若上游新增/删除模块，同步改 `CMakeLists.txt`
   的 `seeta_add_feature` / `seeta_add_jni` 列表与库名；
3. `publishToMavenLocal` 验证 POM/AAR/sources。

## 维护者须知：本模块里的适配层

子模块零修改，所有现代工具链兼容都通过本模块的胶水完成，升级时优先检查这些点：

- `cmake/seeta_compat.h`：NDK r23+ 的 libc++ 不再隐式传递 `<functional>` 等头，
  对全部 SeetaFace 编译单元强制包含。
- `seeta_v2_compat.h`（构建时生成）：SeetaFace6JNI 面向更新版
  `seeta::ModelSetting_v2`（字符串 device），而 index 钉选的原生头只有枚举版
  `seeta::ModelSetting`，这里做了隐式转换适配。若未来 index 升级到含 v2 的版本，
  可删除该垫片。
- 头文件 shim：JNI 代码中的 `Common/...`、`src/seeta/...`、
  `seeta/cTrackingFaceInfo.h`（小写 c，Linux 下与真实文件 `CTrackingFaceInfo.h`
  大小写不一致；且 Windows 上转发头会自引用）通过构建目录中的转发头/强制包含解决。
- JNI 符号桥接（`src/main/cpp/bridges/`，经 CMakeLists 末尾的
  `target_sources` 接入）：上游 Java 声明与 C++ 导出不一致的两处运行时地雷。
  ① `QualityOfBirghtness`（类名拼错，少个 i）四个方法的实现却以正确拼写
  `QualityOfBrightness` 导出，JNI 按精确名匹配永远找不到；桥接按拼错名转发。
  ② `FaceDetector` 的 10 个便捷 Set/Get 方法在 Java 里声明为 native 但上游
  从未实现；桥接基于原生 `set/get(Property, ...)` 补齐。上游若修复可删除。
- `scripts/check_jni_symbols.py`：把全部 Java native 声明与 JNI 仓库 +
  bridges 目录的 C++ 导出符号交叉校验（145 项应全部 OK），升级上游后先跑
  `python scripts/check_jni_symbols.py third_party/SeetaFace6JNI src/main/cpp/bridges`。
- TenniS 的 NEON/SIMD kernel **已禁用**（`TS_USE_NEON/TS_USE_SIMD` 不定义）：
  NDK 27 下 NEON 路径在真机上堆损坏（depthwise conv / resize 越界写 → 之后任意
  delete 段错误；与 OpenMP 链接方式、优化级别无关，已逐项排除）。当前走通用
  C++ kernel，稳定但速度下降。恢复 NEON 需用 NDK r16b 验证或等上游修复。
  同时不要恢复上游 Android.mk 的 `-ffast-math` 系列参数（同样引发堆损坏）。
- 源文件清单与链接关系**逐字对照**每个上游模块的
  `<Module>/<Component>/android/jni/Android.mk`（官方 ndk-build 配置），
  而非其桌面 CMakeLists（后者带强制安装路径、find_package 等副作用）。

## 许可证

SeetaFace6 各组件为 **BSD-2-Clause**（SeetaTech / 中科院计算所）。
注意：**SeetaFace6JNI 仓库本身没有 LICENSE 文件**，本库按与引擎相同的 BSD-2-Clause
处理并在 POM 中注明；在任何公开发布前建议向上游作者确认 JNI 代码授权。
