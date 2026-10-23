package moe.fuqiuluo.xposed.hooks.camera

import android.content.Context
import moe.fuqiuluo.xposed.utils.XposedHelpers
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger

/**
 * 被注入进程里的 `Application`（跨 provider 通信要用它拿 `ContentResolver`）。
 *
 * 为什么延迟解析而不是在 `handleLoadPackage` 里取：那一刻 Application 往往还没建好
 * （hook 发生在 `Application.onCreate` 之前）。摄像头模拟的入口是"应用申请摄像头"，
 * 那时 Application 必然已经存在 —— 所以按需取一次、进程内缓存即可。
 */
internal object CameraMockEnv {

    @Volatile
    private var cached: Context? = null

    @Volatile
    private var resolved = false

    fun appContext(): Context? {
        if (resolved) return cached
        synchronized(this) {
            if (resolved) return cached
            cached = runCatching {
                val activityThread = XposedHelpers.findClass("android.app.ActivityThread", null)
                XposedHelpers.callStaticMethod(activityThread, "currentApplication") as? Context
            }.onFailure {
                if (FakeLoc.enableDebugLog) Logger.debug("摄像头模拟：取 Application 失败（${it.message}）")
            }.getOrNull()
            // 只有真的拿到了才标记解析完成：拿不到时下次再试（Application 可能还没建）
            if (cached != null) resolved = true
            return cached
        }
    }
}

/** 供同包各处直接使用的简写 */
internal fun appContextOf(): Context? = CameraMockEnv.appContext()
