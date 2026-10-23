package moe.fuqiuluo.portalex.vcam

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import java.util.concurrent.TimeUnit

/**
 * 临时真/假相机切换器。
 *
 * 假：停 vendor provider，启动 1 号机 AIDL provider。
 * 真：杀掉 AIDL provider，启动 vendor provider。
 *
 * 仅用于调试/真机验证；正式产品应由模块提供受控命令，而不是让 app 直接 su。
 */
object CameraRealFake {

    private const val PROVIDER = "/data/local/tmp/portal_camera_provider"
    private const val META = "/sdcard/GlaDos_File/OnePlusProvider/portal_meta.bin"
    private const val TAGS = "/sdcard/GlaDos_File/OnePlusProvider/portal_tags.bin"

    /**
     * 系统级强制重载：重启 cameraserver，让所有 CameraService 客户端
     * 收到断开，并重新枚举 provider。不要重启 servicemanager。
     */
    fun reloadAllCameras(context: Context): Result<String> = runCatching {
        val p = ProcessBuilder("su", "-c", "setprop ctl.restart cameraserver")
            .redirectErrorStream(true)
            .start()
        val output = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(12, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            error("restart cameraserver timeout")
        }
        if (p.exitValue() != 0) error("restart cameraserver exit=${p.exitValue()} $output")
        val deadline = SystemClock.elapsedRealtime() + 10_000
        val manager = context.getSystemService(CameraManager::class.java)
        while (SystemClock.elapsedRealtime() < deadline) {
            val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
            if (ids.isNotEmpty()) return@runCatching "cameras=" + ids.joinToString(",")
            Thread.sleep(250)
        }
        error("cameraserver not ready after restart")
    }

    fun setFake(context: Context, fake: Boolean): Result<String> = runCatching {
        val cmd = if (fake) {
            "setprop ctl.stop vendor.camera-provider; " +
                "kill $(pidof portal_camera_provider) 2>/dev/null; sleep 1; " +
                "PORTAL_META=$META PORTAL_TAGS=$TAGS PORTAL_INSTANCE=vendor_qti/0 " +
                "nohup $PROVIDER >/data/local/tmp/portal_provider.log 2>&1 &"
        } else {
            "kill $(pidof portal_camera_provider) 2>/dev/null; " +
                "setprop ctl.start vendor.camera-provider"
        }
        val p = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val output = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(12, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            error("su timeout")
        }
        if (p.exitValue() != 0) error("su exit=${p.exitValue()} $output")
        // 切换后 cameraserver 重新枚举需要时间；直接轮询原相机列表，而不是盲等固定秒数。
        val deadline = SystemClock.elapsedRealtime() + 8_000
        val manager = context.getSystemService(CameraManager::class.java)
        while (SystemClock.elapsedRealtime() < deadline) {
            val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
            val ready = if (fake) ids.contains("1") else ids.isNotEmpty()
            if (ready) return@runCatching output + " cameras=" + ids.joinToString(",")
            Thread.sleep(250)
        }
        error("camera provider not ready after 8s")
    }
}
