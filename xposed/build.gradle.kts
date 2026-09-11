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
    compileOnly(libs.xposed.api)
    compileOnly(project(":system-api"))
    implementation(project(":nmea"))
    // 解析平台库 mini debug info（.gnu_debugdata，xz）用；随模块 dex 进 system_server
    implementation(libs.xz)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
