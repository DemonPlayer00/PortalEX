package moe.fuqiuluo.portalex

import android.app.Application
import android.content.Context
import com.baidu.location.LocationClient
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer
import moe.fuqiuluo.portalex.ext.frameRecord
import moe.fuqiuluo.portalex.service.PortalLocationClient

class Portal: Application() {

    override fun onCreate() {
        super.onCreate()

        SDKInitializer.setAgreePrivacy(this, true)
        LocationClient.setAgreePrivacy(true)

        SDKInitializer.initialize(this)
        SDKInitializer.setCoordType(DEFAULT_COORD_TYPE)

        appContext = applicationContext

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