import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "cn.heavenlybook.sf"
version = libs.versions.seetaFace.get()

/**
 * The upstream SeetaFace6JNI repository ships one copy of the shared data
 * classes (SeetaImageData, SeetaModelSetting, ...) inside every module folder.
 * This task walks all of them, places each .java according to its `package`
 * declaration (FaceTracker.java physically sits outside the sdk/ folder), and
 * fails the build if two same-named classes ever diverge, so an upstream bump
 * can't silently pick one copy at random.
 */
abstract class MergeSeetaJniSources : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jniRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun merge() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        val packageRegex = Regex("""(?m)^\s*package\s+([A-Za-z0-9_.]+)\s*;""")
        val merged = linkedMapOf<String, File>()

        jniRoot.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .sortedBy { it.invariantSeparatorsPath }
            .forEach { source ->
                val text = source.readText(Charsets.UTF_8)
                val pkg = packageRegex.find(text)?.groupValues?.get(1)
                    ?: throw GradleException("SeetaFace6JNI source without package declaration: $source")
                val relativePath = pkg.replace('.', '/') + "/" + source.name
                val previous = merged[relativePath]
                if (previous != null) {
                    fun normalized(f: File) = f.readText(Charsets.UTF_8).replace("\r\n", "\n").trim()
                    if (normalized(previous) != normalized(source)) {
                        throw GradleException(
                            "Conflicting copies of $relativePath in SeetaFace6JNI:\n" +
                                "  ${previous.path}\n  ${source.path}\n" +
                                "Align the upstream modules or add an explicit pick rule in build.gradle.kts."
                        )
                    }
                } else {
                    val destination = File(out, relativePath)
                    destination.parentFile!!.mkdirs()
                    source.copyTo(destination, overwrite = true)
                    merged[relativePath] = source
                }
            }

        logger.lifecycle("Merged ${merged.size} SeetaFace6JNI Java classes from $jniRoot")
    }
}

val mergeSeetaJniSources = tasks.register<MergeSeetaJniSources>("mergeSeetaJniSources") {
    jniRoot.set(file("third_party/SeetaFace6JNI"))
    outputDir.set(layout.buildDirectory.dir("generated/sources/seetaJni/java"))
}

android {
    namespace = "cn.heavenlybook.sf.face"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 24

        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DSEETA_BUILD_JNI=ON",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    // The published API is the upstream Java package com.seeta.sdk, bundled
    // together with the native libraries; no Android resources are needed.
    buildFeatures {
        androidResources {
            enable = false
        }
    }

    publishing {
        singleVariant("release")
    }

    lint {
        abortOnError = false
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(
            mergeSeetaJniSources,
            MergeSeetaJniSources::outputDir,
        )
    }
}

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(mergeSeetaJniSources)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "cn.heavenlybook.sf"
            artifactId = "face-sf"
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }
            artifact(sourcesJar)

            // 仓库主页/scm 从 git remote origin 推导(当前为 Gitea),
            // 无 origin 时回退 GitHub 占位。
            val originUrl = runCatching {
                providers.exec {
                    commandLine("git", "remote", "get-url", "origin")
                }.standardOutput.asText.get().trim().removeSuffix(".git")
            }.getOrNull()

            pom {
                name.set("face-sf")
                description.set(
                    "Android AAR packaging SeetaFace6 (index umbrella) native libraries and the " +
                        "official SeetaFace6JNI Java bindings (com.seeta.sdk), built from upstream git submodules."
                )
                url.set(originUrl ?: "https://github.com/heavenlybook/sf")

                licenses {
                    license {
                        name.set("BSD-2-Clause")
                        url.set("https://github.com/SeetaFace6Open/index/blob/master/LICENSE")
                        distribution.set("repo")
                        comments.set(
                            "SeetaFace6 modules are BSD-2-Clause. The SeetaFace6JNI repository does not " +
                                "ship a LICENSE file; clarify with its authors before any public redistribution."
                        )
                    }
                }
                scm {
                    connection.set(originUrl?.let { "scm:git:$it.git" } ?: "scm:git:git://github.com/heavenlybook/sf.git")
                    developerConnection.set(
                        originUrl?.let { "scm:git:$it.git" }
                            ?: "scm:git:ssh://github.com/heavenlybook/sf.git",
                    )
                    url.set(originUrl?.plus("/tree/master") ?: "https://github.com/heavenlybook/sf/tree/master")
                }
            }
        }
    }

    repositories {
        // publishToMavenLocal 始终可用。远程目标二选一:
        //  1. Gitea(默认):URL 从 git remote origin 自动推导
        //     https://host/owner/repo.git -> https://host/api/packages/owner/maven
        //  2. 通用属性 seeta.maven.url(显式覆盖)
        // 凭证来自 seeta.maven.user / seeta.maven.token 属性或
        // GITEA_USER / GITEA_TOKEN 环境变量,一律不入仓库。
        fun giteaMavenUrl(): String? {
            providers.gradleProperty("seeta.maven.url").orNull?.let { return it }
            return runCatching {
                val remote = providers.exec {
                    commandLine("git", "remote", "get-url", "origin")
                }.standardOutput.asText.get().trim()
                Regex("""^(https?://[^/]+)/([^/]+)/([^/]+?)(?:\.git)?$""")
                    .find(remote)
                    ?.let { "${it.groupValues[1]}/api/packages/${it.groupValues[2]}/maven" }
            }.getOrNull()
        }
        val giteaUrl = giteaMavenUrl()
        if (giteaUrl != null) {
            maven {
                name = "gitea"
                url = uri(giteaUrl)
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
        // 保留旧的显式属性通道,便于发布到非 Gitea 仓库。
        val remoteUrl = providers.gradleProperty("seeta.maven.url").orNull
        if (remoteUrl != null && remoteUrl != giteaUrl) {
            maven {
                name = "SeetaRelease"
                url = uri(remoteUrl)
                credentials {
                    username = providers.gradleProperty("seeta.maven.user").orNull
                    password = providers.gradleProperty("seeta.maven.token").orNull
                }
            }
        }
    }
}
