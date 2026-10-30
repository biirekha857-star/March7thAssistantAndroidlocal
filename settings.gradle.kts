pluginManagement {
    repositories {
        // ⚠️ 国内镜像必须放在**官方源之前**：Gradle 按声明顺序逐个尝试，
        // 官方源在国内基本连不上，会一直卡住/超时。
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }

        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        gradlePluginPortal()
        mavenLocal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

includeBuild("build-logic")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 同上：镜像优先，否则依赖解析会卡在官方源上
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }

        google()
        maven { url = uri("https://jitpack.io") }
        mavenCentral()
    }
}

rootProject.name = "March7thAssistantAndroid"
include(":app")
include(":hidden-api")
include(":annotation-api")
include(":ksp-processor")
 
