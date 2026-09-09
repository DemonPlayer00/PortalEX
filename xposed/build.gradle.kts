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
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
        ndk {
            abiFilters.addAll(arrayOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
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
    buildFeatures {
        prefab = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    compileOnly(libs.xposed.api)
    compileOnly(project(":system-api"))
    implementation(project(":nmea"))

    // Just Test?
    //noinspection GradleDynamicVersion
    //implementation("org.lsposed.lsplant:lsplant-standalone:+")
    //noinspection UseTomlInstead
    implementation("io.github.vvb2060.ndk:dobby:1.2")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
