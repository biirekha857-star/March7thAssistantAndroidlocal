import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    id("com.march7th.hkrpg.asset-manifest")
    id("com.march7th.hkrpg.i18n-verify")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val gitVersionCode: Int by lazy {
    // 0.5.5beta 是云游戏客户端支持的里程碑版本。
    //
    // 原来的实现是 `git rev-list --count HEAD`，跟着提交数一直涨 ——
    // 但本项目已经脱离上游的版本节奏（上游 0.22.0 是明日方舟的），
    // 继续沿用会闹出「版本号比上游还大」的困惑。
    // 所以这里**固定写死**，与 git 历史解耦。
    //
    // 版本号对应关系（手工维护）：
    //   3  → 0.0.3alpha  首个对外测试版
    //   5  → 0.5.0beta   每日实训 + 全部副本导航可用
    //   6  → 0.5.5beta   云游戏启动 + 长屏采集修复 + 公告弹窗
    //   7  → 0.5.6beta   画面异常自愈提醒 + 首页/引导/主题文案调整
    //   8  → 0.5.7beta   界面重构（首页合并/卡片流/时间线/设置分类）+ 应用内更新提示
    8
}

val gitVersionName: String by lazy {
    "0.5.7beta"
}

// 本机默认只编 arm64、关 LTO；CI=true 保持双 ABI + LTO
// -Pmaa.abi=all|arm64-v8a|x86_64 或 local.properties 覆盖
val ci = System.getenv("CI")?.equals("true", ignoreCase = true) == true
val abiRaw = (findProperty("maa.abi") as String?)?.trim().orEmpty()
    .ifEmpty { localProperties.getProperty("maa.abi", "").trim() }
    .ifEmpty { if (ci) "all" else "arm64-v8a" }
val nativeAbis: List<String> = if (abiRaw.equals("all", ignoreCase = true)) {
    listOf("arm64-v8a", "x86_64")
} else {
    abiRaw.split(',', ' ').map(String::trim).filter(String::isNotEmpty)
}
val nativeLto = when (
    (findProperty("maa.nativeLto") as String?)?.trim()
        ?: localProperties.getProperty("maa.nativeLto", "")
) {
    "true" -> true
    "false" -> false
    else -> ci
}
println("[ABI] ${nativeAbis.joinToString()}")
println("[native] LTO=$nativeLto")
println("[Java Version] ${System.getProperty("java.version")}")

android {
    namespace = "com.march7th.hkrpg"
    compileSdk = 37

    /**
     * 排除上游遗留的旧源码树 `com/aliothmoon/maameow/`。
     *
     * ## 为什么必须排除
     *
     * 本工程是从 MAA-Meow 改造来的，改造时新建了 `com/march7th/hkrpg/`
     * 作为实际使用的包，但上游那份 `com/aliothmoon/maameow/` **没有删掉**，
     * 而 Gradle 默认会把 `src/main/java` 下的**所有**包都编进去。
     *
     * 后果：同一份逻辑被编译两次，且旧树里引用了只存在于旧 namespace 的
     * `BuildConfig` / `R`（namespace 已改成 com.march7th.hkrpg），
     * 于是编译报一堆 `Unresolved reference 'BuildConfig'`。
     *
     * 这个目录只是历史包袱，运行时完全用不到（Manifest 的
     * `.M7aApplication` 按 namespace 解析到新树的 `M7aApplication`）。
     */


    defaultConfig {
        applicationId = "com.march7th.hkrpg"
        minSdk = 28
        targetSdk = 36
        versionCode = gitVersionCode
        versionName = gitVersionName
        println("Build version: versionCode=$versionCode, versionName=$versionName")
        // 原值 "29.0.13113456" 本机没有，重下要 ~1.5GB（C 盘只剩 7.7GB）。
        // 改用本机已安装的 NDK 小版本 —— 同属 29.x，ABI/工具链兼容。
        ndkVersion = "29.0.14206865"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // setup_maa_core.py deploy 时写入 .maaversion；缺失时为空串，运行时检查宽松放行
        val maaCoreVersion = rootProject.file(".maaversion")
            .takeIf { it.isFile }?.readText()?.trim().orEmpty()
        buildConfigField("String", "MAA_CORE_VERSION", "\"$maaCoreVersion\"")

        ndk {
            abiFilters.addAll(nativeAbis)
        }
        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DMAA_NATIVE_LTO=${if (nativeLto) "ON" else "OFF"}",
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: localProperties.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: localProperties.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: localProperties.getProperty("KEY_ALIAS", "")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: localProperties.getProperty("KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        val minifyProguardFiles = listOf(
            getDefaultProguardFile("proguard-android-optimize.txt").absolutePath,
            "proguard-rules.pro",
        )
        // local.properties: maa.debugR8=true 时 debug 也走 R8
        val debugR8 = localProperties.getProperty("maa.debugR8", "false").toBoolean()
        getByName("debug") {
            isMinifyEnabled = debugR8
            isShrinkResources = debugR8
            if (debugR8) {
                proguardFiles(*minifyProguardFiles.toTypedArray())
                println("[R8] debug minify+shrink enabled (maa.debugR8=true)")
            }
        }
        release {
            // ## R8 强度可配（开源项目不需要重度混淆）
            //
            // | maa.r8 | 代码混淆 | 资源压缩 | 内存占用 | 用途 |
            // |---|---|---|---|---|
            // | `none`  | ❌ | ❌ | 低 | **默认**（开源发布 / 低配机编译） |
            // | `light` | ✅ | ❌ | 中 | 需要减小体积时 |
            // | `full`  | ✅ | ✅ | 高 | 上架商店 |
            //
            // 资源压缩（`isShrinkResources`）最吃内存 —— 它要把整个
            // resources.arsc 和所有资源文件过一遍，实测在 8GB 机器上
            // 会把 Gradle + Kotlin 两个进程挤爆、卡死在 GC。
            //
            // 开源项目本来就不怕反编译（源码都公开），所以默认 `none`：
            // 不混淆、不压缩。
            //
            // ## 为什么默认从 `light` 改成 `none`
            //
            // R8 会把**只被 JNI 通过反射访问**的类当成无用代码删掉，
            // 而 ProGuard 规则很难穷举这类引用。实测踩过两次：
            // `ai.onnxruntime.TensorInfo` 被删 → OCR 时 JNI GetMethodID
            // 直接崩溃（见 7afe71f）；AIDL 改名后回调类也被删。
            //
            // 这类问题**只在 release 包出现**，debug 完全正常，
            // 定位成本极高。既然源码公开、体积也不是瓶颈，
            // 默认关掉 R8 最省事；确实要瘦身时用 `-Pmaa.r8=light`。
            val r8Mode = (
                (findProperty("maa.r8") as String?)?.trim()
                    ?: localProperties.getProperty("maa.r8", "")
                ).ifEmpty { "none" }.lowercase()

            when (r8Mode) {
                "none" -> {
                    isMinifyEnabled = false
                    isShrinkResources = false
                    println("[R8] mode=none —— 不混淆不压缩")
                }
                "full" -> {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(*minifyProguardFiles.toTypedArray())
                    println("[R8] mode=full —— 混淆 + 资源压缩（需要 ≥8GB 空闲内存）")
                }
                else -> {
                    isMinifyEnabled = true
                    isShrinkResources = false
                    proguardFiles(*minifyProguardFiles.toTypedArray())
                    println("[R8] mode=light —— 只混淆代码，不压缩资源")
                }
            }

            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: localProperties.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
                println("[Signing] Using release keystore: $keystorePath")
            } else {
                println("[Signing] No release keystore configured, release build will not be signed")
            }
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        aidl = true
        compose = true
    }

    // ## 原生库编译开关
    //
    // 默认走 CMake 现场编译 `libbridge.so`（屏幕采集 + 触摸注入）。
    //
    // 但本机 NDK 可能损坏（实测过：`clang.exe` 缺失，只剩 0KB 的包装脚本），
    // 这时 release 构建会在 `configureCMakeRelWithDebInfo` 直接失败。
    //
    // 兜底：`local.properties` 里设 `maa.skipNative=true`，
    // 就把 debug 已经编好的 `.so` 打包进去，跳过现场编译。
    //
    // ⚠️ 只适合"native 代码没改过"的场景 —— 改了 C++ 就必须真编译。
    val skipNative = localProperties.getProperty("maa.skipNative", "false").toBoolean()
    if (skipNative) {
        println("[native] maa.skipNative=true —— 跳过 CMake，复用已有 .so")
    }

    if (!skipNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/native/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            pickFirsts += setOf(
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        localeFilters += listOf("zh", "en")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(project(":hidden-api"))
    implementation(project(":annotation-api"))
    ksp(project(":ksp-processor"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.exifinterface)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.window)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Third-party
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.fastjson2)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu)
    implementation(libs.device.compat)
    implementation(libs.xx.permissions)
    implementation(libs.floatingx)
    // 上游 0.22.0 漏引了 floatingx-compose，而 OverlayController 用了
    // `com.petterp.floatingx.compose.enableComposeSupport`，缺它编译不过。
    implementation(libs.floatingx.compose)
    // 崩铁内核：模板匹配需要 OpenCV（上游 MaaCore 自带识别，故原本没有此依赖）
    implementation(libs.opencv.lib)
    // 崩铁内核：离线 OCR（PP-OCRv6 det + rec），选副本/读体力数值都靠它
    implementation(libs.onnxruntime.android)
    implementation(libs.sonner)
    implementation(libs.timber)
    implementation(libs.okhttp)
    implementation(libs.angus.mail)
    implementation(libs.angus.activation)
    implementation(libs.jakarta.activation.api)
    implementation(libs.reorderable)
    implementation(libs.compose.markdown)

    // sora-editor：JSON 语法高亮编辑器（TextMate + darcula 主题）
    implementation(platform(libs.bom))
    implementation(libs.editor)
    implementation(libs.editor.language.textmate)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xzakota.focus.api)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// 成就字符串资源查表生成任务已删除。
// 它原本为 MAA-Meow 自带的「元成就」系统（帕拉斯勋章等）生成
// achievementStringResId() 查表，避免用 Resources.getIdentifier。
// 那套成就系统与崩铁游戏内成就无关，已整体移除，所以这里不再需要生成。
//
// 连带删掉的还有 306 条 achievement_* 字符串资源。

