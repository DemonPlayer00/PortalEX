package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地素材的**自动上传**。
 *
 * 设计口径：
 *  · 有注入框架时，录完/回到前台后自动把本地库同步到 system_server，不再需要用户手动点；
 *  · 没有框架时静默失败，本地文件与偏好原样保留，视频行继续显示「重试上传 / 导出」；
 *  · **只走注入框架的抽象 socket，不触发 root 提权** —— 自动流程不应在后台弹 root 授权，
 *    也不该在没有框架的机器上反复尝试 su；
 *  · 同一时刻只跑一个导入；成功后只标记本次快照里的待上传 id，避免把导入期间新录的视频误标为已上传。
 */
object CameraAutoUpload {

    private const val TAG = "CameraAutoUpload"

    /** 自动尝试的最小间隔：避免每次 onResume 都去连一次不存在的 socket。 */
    private const val MIN_INTERVAL_MS = 15_000L

    private val running = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    @Volatile
    private var lastAttemptAt = 0L
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var rerunRequested = false

    /** 本地库里还没标记为已上传的素材 id。 */
    fun pendingIds(context: Context): List<String> {
        val uploaded = CameraMockUploadState.uploadedIds(context)
        return CameraMockStore.points(context)
            .flatMap { it.videos }
            .map { it.id }
            .filterNot { it in uploaded }
    }

    /**
     * 触发一次自动上传。
     *
     * @param force 录制刚结束时用 true，跳过 15s 节流；页面 onResume 用 false。
     * @param onFinished 上传结束（成功/失败）后回主线程回调，页面可据此刷新按钮。
     */
    fun trigger(
        context: Context,
        force: Boolean = false,
        onFinished: ((Boolean) -> Unit)? = null,
    ) {
        val app = context.applicationContext
        appContext = app
        val pending = pendingIds(app)
        if (pending.isEmpty()) return

        synchronized(this) {
            if (running.get()) {
                // 让后来者也能在上传结束时刷新，而不是拿不到回调
                if (onFinished != null) listeners.add(onFinished)
                // 若正好有上传在跑（可能是上一次的整库导入），这次新增的素材不能丢：
                // 结束后再补一次；成功后 pending 为空会自然短路。
                rerunRequested = true
                return
            }
            val now = System.currentTimeMillis()
            if (!force && now - lastAttemptAt < MIN_INTERVAL_MS) return
            lastAttemptAt = now
            running.set(true)
            if (onFinished != null) listeners.add(onFinished)
            rerunRequested = false
        }

        Thread({
            val ok = runCatching { CameraMockSyncClient.importLibraryViaFramework() }
                .onFailure { Log.w(TAG, "import failed", it) }
                .getOrDefault(false)
            if (ok) {
                CameraMockUploadState.markUploaded(app, pending)
                Log.i(TAG, "auto uploaded ${pending.size} item(s)")
            } else {
                // 没有框架/系统侧未就绪：保留本地，重试入口照常显示。
                // 不写 failed 集合（needsRetry 只看 uploaded），避免每次 onResume 都落一次盘。
                Log.i(TAG, "auto upload unavailable, kept ${pending.size} item(s) locally")
            }
            running.set(false)
            val callbacks = listeners.toList()
            listeners.clear()
            if (callbacks.isNotEmpty()) {
                Handler(Looper.getMainLooper()).post { callbacks.forEach { it(ok) } }
            }
            if (rerunRequested) {
                rerunRequested = false
                appContext?.let { trigger(it, force = true) }
            }
        }, "PortalEX-CameraAutoUpload").start()
    }
}
