plugins {
    alias(libs.plugins.android.library)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "moe.fuqiuluo.xposed"
    compileSdk = 36

    // 与 :app 保持同一个 NDK：本模块的 native 层会被注入 system_server，
    // 工具链与平台库（aarch64）对齐，避免 ABI/链接期差异。
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // Binder 外周传感器模拟只需要 arm64-v8a（现役真机）与 x86_64（模拟器调试）
            abiFilters.clear()
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
        externalNativeBuild {
            cmake {
                // 纯 C，不链接 libc++：注入系统进程的库不引入额外运行时依赖
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 旧 XposedBridge API：过渡期仍在用（S4 之后只有 Hooks.kt 适配层引用）。两条入口并存，
    // 直到 libxposed 入口在真机上验证通过再摘。
    compileOnly(libs.xposed.api)
    // libxposed 现代 API。**compileOnly 是硬要求**：这些类由框架的 framework.dex 提供并注入
    // 目标进程，打进模块就成了第二份实现（还会和框架的类冲突）。
    compileOnly(libs.libxposed.api)
    compileOnly(project(":system-api"))
    implementation(project(":nmea"))
    // 解析平台库 mini debug info（.gnu_debugdata，xz）用；随模块 dex 进 system_server
    implementation(libs.xz)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
