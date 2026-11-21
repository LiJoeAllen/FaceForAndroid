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
