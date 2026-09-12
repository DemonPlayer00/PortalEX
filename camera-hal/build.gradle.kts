plugins {
    alias(libs.plugins.android.library)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/**
 * 摄像头 HAL 包装层（Magisk 模块 portalex-camera-hal 的载荷）。
 *
 * 为什么单独一个模块：它的目标 **只有 32 位（armeabi-v7a）** —— 实测 MI6 上
 * camera provider 是 32 位进程（`/vendor/lib/hw/camera.msm8998.so`），而 `:xposed`
 * 的 native 目标只出 arm64-v8a / x86_64（那是注入 system_server 用的）。
 * 把它混进 :xposed 会为了一个 32 位 HAL 把整个模块 APK 也带上 32 位，没必要。
 *
 * 这个模块产出的 .so 不随 APK 分发，而是由 Magisk 模块挂到
 * `/vendor/lib/hw/camera.msm8998.so`（部署脚本 scripts/deploy-camera-hal.sh）。
 */
android {
    namespace = "moe.fuqiuluo.camerahal"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters.clear()
            abiFilters.add("armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
