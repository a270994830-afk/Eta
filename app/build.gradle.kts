import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 影子屏 sidecar 载体的构建。
//
// carrier 源码编译为独立 dex 并打入 assets/carrier/carrier.dex，运行时由 root 解包供
// app_process 加载。构建期找可用的 POSIX shell（容器/Linux 用 /bin/bash，Windows 用 Git Bash）
// 调用构建脚本，由脚本结合 Android SDK（javac + d8）产出 dex；无可用 shell 时跳过并沿用已提交的 dex。
val posixShell: String? = run {
    val candidates = listOfNotNull(
        System.getenv("GIT_INSTALL_ROOT")?.let { "$it/bin/bash.exe" },
        "C:/Program Files/Git/bin/bash.exe",
        "C:/Program Files (x86)/Git/bin/bash.exe",
        "/bin/bash",
    )
    candidates.firstOrNull { File(it).isFile }
}

val androidSdkDir: String? = run {
    val props = rootProject.file("local.properties")
    if (props.isFile) {
        props.readLines()
            .firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter("sdk.dir=")
            ?.trim()
            ?.replace("\\\\", "\\")
            ?.replace("\\:", ":")
            ?.replace("\\", "/")
    } else {
        System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
    }
}

val carrierScript = rootProject.file("carrier/build_carrier.sh")
val carrierSrcDir = rootProject.file("carrier/src")

val carrierDexOutput = layout.projectDirectory.file("src/main/assets/carrier/carrier.dex")

// 在配置期求值，避免执行期访问 project（与 configuration cache 冲突）。
val carrierBuildEnabled = posixShell != null && carrierScript.isFile

val buildCarrierDex = tasks.register<Exec>("buildCarrierDex") {
    group = "shadow"
    description = "编译影子屏 sidecar 载体到 assets/carrier/carrier.dex"
    // 配置期即可确定的启用条件，避免执行期闭包捕获脚本对象（configuration cache 限制）。
    enabled = carrierBuildEnabled
    workingDir = rootProject.projectDir
    commandLine(
        buildList {
            add(posixShell ?: "bash")
            add(carrierScript.absolutePath)
            androidSdkDir?.let { add(it) }
        },
    )
    isIgnoreExitValue = true
    inputs.file(carrierScript)
    inputs.dir(carrierSrcDir)
    outputs.file(carrierDexOutput)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(buildCarrierDex)
}

val releaseStoreFile = System.getenv("ETA_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("ETA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("ETA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ETA_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

android {
    namespace = "io.github.mangi.eta"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.github.mangi.eta"
        minSdk = 34
        targetSdk = 36
        ndk {
            abiFilters += "arm64-v8a"
        }
        // versionCode 规则：yyyyMMdd + 两位当日序号（01 起），发版时随 versionName 一起手动递增。
        versionCode = 2026101201
        versionName = "3.0.6"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isPseudoLocalesEnabled = true
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = false
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "b+zh+Hans", "b+zh+Hant")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libproot_exec.so", "**/libproot_loader.so", "**/libeta_pty.so")
        }
        resources {
            // 合并 Xposed 模块声明，避免 release 裁剪后模块入口失效
            merges += "META-INF/xposed/*"
            // 仅排除会引发打包冲突的签名/版本元数据，避免误伤 Compose 资源
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.commons.compress)
    implementation(libs.xz)
    compileOnly(libs.libxposed.api)
    // UI 侧 RemotePreferences 写入桥：通过 XposedService 将配置提交到 LSPosed 数据库；
    // Hook 侧用 XposedInterface.getRemotePreferences 读取当前进程持有的配置缓存。
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    // markdown-renderer-m3 将 material3 作为 compileOnly，需显式引入以满足运行时依赖
    implementation(libs.material3)
    implementation(libs.hidden.api.bypass)

    // Shizuku：免 Root 的 ADB/Root 特权通道（对齐 Operit 的 DEBUGGER 权限层级）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // DataStore：Provider / Model 结构化 JSON 与当前选中 ID 等键值
    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp：替代 HttpURLConnection，支持 SSE
    implementation(libs.okhttp)
    implementation(libs.liquid)
    implementation(libs.backdrop)
    implementation(libs.okhttp.sse)

    // Kotlinx Serialization：Provider 设置与运行时配置 JSON
    implementation(libs.kotlinx.serialization.json)

    // Coroutines：显式引入，避免依赖传递版本不确定
    implementation(libs.kotlinx.coroutines.android)

    // Porcupine：轻量语音唤醒词检测（EtaHotwordService）
    implementation("ai.picovoice:porcupine-android:4.0.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
}
