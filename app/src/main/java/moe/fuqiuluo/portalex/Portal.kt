package moe.fuqiuluo.portalex

import android.app.Application
import android.content.Context
import com.baidu.location.LocationClient
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer
import com.google.android.material.color.DynamicColors
import moe.fuqiuluo.portalex.ext.frameRecord
import moe.fuqiuluo.portalex.service.PortalLocationClient
import moe.fuqiuluo.portalex.crash.CrashHandler

class Portal: Application() {

    override fun onCreate() {
        super.onCreate()

        // 莫奈取色（Monet）：Android 12+ 由壁纸生成整套 M3 语义色（含深浅色）。
        // 必须在这里注册——DynamicColors 是 ActivityLifecycleCallbacks，晚于首个
        // Activity 创建再调用，那一个页面就拿不到壁纸色。
        // Android 11 及以下没有壁纸取色能力，此调用是 no-op，继续用主题里的品牌灰。
        DynamicColors.applyToActivitiesIfAvailable(this)

        SDKInitializer.setAgreePrivacy(this, true)
        LocationClient.setAgreePrivacy(true)

        SDKInitializer.initialize(this)
        SDKInitializer.setCoordType(DEFAULT_COORD_TYPE)

        appContext = applicationContext

        // 全局最顶层异常展示（独立 :crash 进程）。
        if (!applicationContext.packageName.endsWith(":crash")) {
            CrashHandler.install(this)
        }

        // 「逐帧记录」打开 ⇒ 客户端逐帧记录随进程启动（默认关闭，不改变任何既有行为）。
        // 与「调试日志」分开：后者会打开模块侧的日志洪水，做时延类测量时是扰动源。
        if (applicationContext.frameRecord) {
            PortalLocationClient.startDebugRecording(applicationContext)
        }


    }

    companion object {
        val DEFAULT_COORD_TYPE = CoordType.GCJ02
        const val DEFAULT_COORD_STR = "GCJ02"

        lateinit var appContext: Context
        //val DEFAULT_COORD_TYPE = CoordType.BD09LL
        //const val DEFAULT_COORD_STR = "bd09ll"
    }
}