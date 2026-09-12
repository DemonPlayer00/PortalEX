pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven ( url = "https://maven.aliyun.com/nexus/content/repositories/google")
        maven ( url = "https://maven.aliyun.com/nexus/content/groups/public/" )
        maven ( url = "https://maven.aliyun.com/nexus/content/repositories/jcenter")
        google()
        mavenCentral()
        maven (url = "https://maven.pkg.jetbrains.space/public/p/ktor/eap")
        maven (url = "https://api.xposed.info/" )
        maven (url = "https://jitpack.io" )
    }
}

buildscript {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://storage.googleapis.com/r8-releases/raw")
        }
    }
    dependencies {}
}

rootProject.name = "Portal"
include(":app")
include(":xposed")
include(":system-api")
include(":nmea")
// 摄像头 HAL 包装层（Magisk 模块载荷，32 位；见 camera-hal/build.gradle.kts）
include(":camera-hal")
