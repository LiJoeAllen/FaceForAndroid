pluginManagement {
    repositories {
        // 国内加速：阿里云镜像优先，官方仓库兜底；镜像缺包时 Gradle 会自动继续尝试后面的仓库。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内加速：阿里云镜像优先，官方仓库兜底；镜像缺包时 Gradle 会自动继续尝试后面的仓库。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        // FaceSDKLib 的 FileOperator 依赖发布在 JitPack
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.javakam")
            }
        }
        // 本仓库 Gitea 的 Maven 软件包(如 :face:sf 的 AAR)。URL 从 git remote
        // origin 推导;凭证见 face/sf/build.gradle.kts 发布块同款属性/环境变量。
        // 本地开发仍走 project(":face:sf"),此仓库供脱离源码的消费方/CI 解析。
        val originUrl = runCatching {
            providers.exec {
                commandLine("git", "remote", "get-url", "origin")
            }.standardOutput.asText.get().trim().removeSuffix(".git")
        }.getOrNull()
        val giteaMatch = originUrl?.let {
            Regex("""^(https?://[^/]+)/([^/]+)/[^/]+$""").find(it)
        }
        if (giteaMatch != null) {
            maven("${giteaMatch.groupValues[1]}/api/packages/${giteaMatch.groupValues[2]}/maven") {
                content {
                    includeGroup("cn.heavenlybook.sf")
                    includeGroup("cn.heavenlybook.sf.face")
                }
                credentials {
                    username = providers.gradleProperty("seeta.maven.user")
                        .orElse(providers.environmentVariable("GITEA_USER"))
                        .orNull
                    password = providers.gradleProperty("seeta.maven.token")
                        .orElse(providers.environmentVariable("GITEA_TOKEN"))
                        .orNull
                }
            }
        }
    }
}

rootProject.name = "Face"
include(":facemirror")
include(":face:sf")
include(":faceai-demo")


// FaceAISDK 官方仓库以 git submodule 形式引入（见 .gitmodules），
// :FaceSDKLib 直接映射到 submodule 内的模块目录。
// 上游的 build.gradle 是 AGP 8 写法，这里改用本仓库的 AGP 9 适配版构建脚本，
// submodule 保持原样不改动。
include(":face:FaceAISDK_Android:FaceSDKLib")
project(":face:FaceAISDK_Android:FaceSDKLib").apply {
    projectDir = file("face/FaceAISDK_Android/FaceSDKLib")
    // 相对 projectDir 上两级（face/FaceAISDK_Android/FaceSDKLib -> face/），
    // 适配脚本放在仓库的 face/ 目录下，子模块目录保持原样。
    buildFileName = "../../FaceSDKLib.gradle"
}
// 上游仓库根自带 build.gradle（AGP8 写法、声明项目级仓库）。若不屏蔽，
// Gradle 会把它评估为中间项目 :face:FaceAISDK_Android 的构建脚本并与
// FAIL_ON_PROJECT_REPOS 冲突。指向一个不存在的文件即视为空项目。
project(":face:FaceAISDK_Android").buildFileName = "build.gradle.disabled"
