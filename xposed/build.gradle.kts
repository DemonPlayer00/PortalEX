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
    testOptions {
        unitTests {
            // 挂钩语义的 JVM 单测（HooksChainTest）用假框架实现，但 Logger 的兜底路径
            // 会碰 android.util.Log：默认实现缺失时返回默认值，别让"记日志"把测试打断。
            isReturnDefaultValues = true
        }
    }
    // 释放版框架的 `XposedInterface` 没有 MethodParameters 属性（实测 javap 计数为 0），
    // 我们的 Kotlin 调用全部按位置传参所以不受影响；但给编译（含测试）统一加上
    // `-parameters`，免得将来有人写按名调用时踩到"编译期看不出、运行期取不到名"的坑。
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
    }
}

dependencies {
    // 只依赖 libxposed 现代 API。**compileOnly 是硬要求**：这些类由框架的 framework.dex
    // 提供并注入目标进程，打进模块就成了第二份实现（还会和框架的类冲突）。
    // 旧 `de.robv.android.xposed:api` 已彻底移除：入口、挂钩原语、偏好通道
    // 三条路都不再用它（见 docs/libxposed-migration.md）。
    compileOnly(libs.libxposed.api)
    compileOnly(project(":system-api"))
    implementation(project(":nmea"))
    // 解析平台库 mini debug info（.gnu_debugdata，xz）用；随模块 dex 进 system_server
    implementation(libs.xz)

    // libxposed API 在单测里要**运行期**可用：挂钩语义单测（HooksChainTest）用假框架
    // 实现 XposedInterface，主源码里它是 compileOnly（真机上由框架注入）。
    testImplementation(libs.libxposed.api)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
