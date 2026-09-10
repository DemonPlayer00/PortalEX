import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "moe.fuqiuluo.portalex"
    compileSdk = 36

    // 必须显式声明，且与 :xposed 一致。
    // 不声明时 :app 会回退到 AGP 内置默认 NDK 版本（9.3.0 为 28.2.13676358），
    // 若本机/CI 未安装该版本，app 的 ndkPlatform 就处于未配置状态：
    // strip<Variant>DebugSymbols 找不到 strip 工具，只打印一句
    // "missing strip tool for ABI ... Packaging it as is." 便退化为原样拷贝，
    // release 包的 libportal.so 因而仍保留 DWARF 段与构建机绝对路径。
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "moe.fuqiuluo.portalex"
        minSdk = 26
        targetSdk = 35
        versionCode = getVersionCode()
        versionName = "1.0.4" + ".r${getGitCommitCount()}." + getVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("arm64-v8a"))
        }

//        val googleServicesFile = project.file("google-services.json")
//        if (!googleServicesFile.exists()) {
//            throw GradleException("在 CI 环境中必须提供 google-services.json 文件!")
//        }

        manifestPlaceholders["BUGLY_APPID"] = "222f9ef298"

        // 百度地图/定位/检索 SDK 的 AK。百度按「应用包名 + 签名 SHA1」双重校验，
        // 换包名（portal → portalex）或换签名后旧 AK 必然失效。
        //
        // 严禁把真实 AK 写进版本库（含本文件与 AndroidManifest.xml）——它虽随 APK
        // 一起分发，但登记进仓库会让「谁在什么时候换了哪把 AK」变成公开信息，
        // 且会随 fork 扩散。取值优先级：
        //   1. 环境变量 BAIDU_MAP_AK（CI 用 secrets 注入）
        //   2. Gradle 属性 -PBAIDU_MAP_AK=...
        //   3. local.properties（该文件已被 .gitignore 排除）
        //   4. 占位符：构建仍能成功，但检索/逆地理会因 AK 校验失败而不可用
        val baiduMapAk = System.getenv("BAIDU_MAP_AK")
            ?: (project.findProperty("BAIDU_MAP_AK") as String?)
            ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
                Properties().apply { f.inputStream().use { load(it) } }
                    .getProperty("BAIDU_MAP_AK")
            }
        manifestPlaceholders["BAIDU_MAP_AK"] = baiduMapAk?.takeIf { it.isNotBlank() }
            ?: "REPLACE_WITH_YOUR_BAIDU_MAP_AK"

        // 构建环境信息：不得写入构建机公网 IP / 主机名 / 构建路径。
        // 这些值会被固化进 APK 的 AndroidManifest.xml，属于可被对方读取的
        // 模块特征指纹，与 025823e「清除模块特征指纹」的目标直接冲突。
        //
        // 说明：BUGLY_BUILD_ENV 并非 Bugly SDK 读取的键（SDK 只读 BUGLY_APPID /
        // BUGLY_APP_CHANNEL / BUGLY_APP_VERSION / BUGLY_ENABLE_DEBUG / BUGLY_AREA /
        // BUGLY_DISABLE 等），仅为构建方自定义标注，默认空。
        // 两个键都改为环境变量注入，未提供时为空，不做任何外部网络请求。
        manifestPlaceholders["BUGLY_BUILD_ENV"] = System.getenv("BUGLY_BUILD_ENV") ?: ""
        // Bugly 渠道号（SDK 会读取并上报）：默认空，CI 可注入如 "github-actions"。
        manifestPlaceholders["APP_CHANNEL"] = System.getenv("APP_CHANNEL") ?: ""
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            manifestPlaceholders["APP_VERSION"] = defaultConfig.versionName ?: "UnknownVersion"
            manifestPlaceholders["BUGLY_ENABLE_DEBUG"] = "false"
        }

        debug {
            manifestPlaceholders["APP_VERSION"] = "${defaultConfig.versionName}-debug"
            manifestPlaceholders["BUGLY_ENABLE_DEBUG"] = "true"
        }
    }

    // AGP 9：通过 androidComponents 注册输出命名（applicationVariants/BaseVariantOutputImpl 内部 API 已移除）
    val buildVersionName = defaultConfig.versionName ?: "unknown"
    androidComponents {
        onVariants(selector().all()) { variant ->
            val abiName = when (variant.flavorName) {
                "app" -> "all"
                "x64" -> "x86_64"
                else -> variant.flavorName
            }
            variant.outputs.forEach { output ->
                output.outputFileName.set("Portal-v${buildVersionName}-${abiName}.apk")
            }
        }
    }

    flavorDimensions.add("mode")

    productFlavors {
        create("app") {
            dimension = "mode"
            ndk {
                println("Full architecture and full compilation.")
                abiFilters.add("arm64-v8a")
                abiFilters.add("x86_64")
            }
        }
        create("arm64") {
            dimension = "mode"
            ndk {
                println("Full compilation of arm64 architecture")
                abiFilters.add("arm64-v8a")
            }
        }
        create("x64") {
            dimension = "mode"
            ndk {
                println("Full compilation of x64 architecture")
                abiFilters.add("x86_64")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/armeabi/**"
            excludes += "lib/x86/**"
            excludes += "lib/x86_64/libBaiduMapSDK**"
            excludes += "lib/x86_64/libc++_shared.so"
            excludes += "lib/x86_64/libc++_shared.so"
            excludes += "lib/x86_64/liblocSDK8b.so"
            excludes += "lib/x86_64/libtiny_magic.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/DEPENDENCIES.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/dependencies.txt"
            excludes += "/META-INF/LGPL2.1"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/META-INF/services/reactor.blockhound.integration.BlockHoundIntegration"
            excludes += "lib/armeabi/**"
            excludes += "lib/x86/**"
        }
    }
    sourceSets {
        getByName("main").jniLibs.srcDirs("libs")
    }

    configureAppSigningConfigsForRelease(project)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

fun configureAppSigningConfigsForRelease(project: Project) {
    val keystorePath: String? = System.getenv("KEYSTORE_PATH")
    if (keystorePath.isNullOrBlank()) {
        return
    }
    project.configure<ApplicationExtension> {
        signingConfigs {
            create("release") {
                storeFile = file(System.getenv("KEYSTORE_PATH"))
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV2Signing = true
            }
        }
        buildTypes {
            release {
                signingConfig = signingConfigs.findByName("release")
            }
            debug {
                signingConfig = signingConfigs.findByName("release")
            }
        }
    }
}

dependencies {
    implementation(project(":xposed"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    implementation(libs.fastjson)

    // BaiduLBS_Android.jar（百度定位 SDK）运行时依赖 okhttp：
    // 删除会导致 :remote 进程 NoClassDefFoundError: okhttp3/OkHttpClient$Builder
    implementation(libs.okhttp)

    implementation(libs.bugly)

    implementation(libs.geotools)
    implementation(fileTree(mapOf(
        "dir" to "libs",
        "include" to listOf("*.jar")
    )))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

fun getGitCommitCount(): Int {
    return runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("git", "rev-list", "--count", "HEAD"))
        p.inputStream.bufferedReader().readText().trim().toInt()
    }.getOrDefault(1)
}

fun getGitCommitHash(): String {
    return runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short", "HEAD"))
        p.inputStream.bufferedReader().readText().trim()
    }.getOrDefault("unknown")
}

fun getVersionCode(): Int {
    return (System.currentTimeMillis() / 1000L).toInt()
}

fun getVersionName(): String {
    return getGitCommitHash()
}

