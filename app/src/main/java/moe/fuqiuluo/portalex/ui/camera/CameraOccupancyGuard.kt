package moe.fuqiuluo.portalex.ui.camera

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 相机占用计时器：**任何持有相机的地方都必须 start()，到点强制释放**。
 *
 * 为什么需要它：本应用会在"测试/联调"场景里长时间开着相机（预览 + 录制 + 喂帧），
 * 这是小时级的持续负载，会真实地把机器烤热。相机属于"拿到就必须还"的资源，
 * 所以这里给**所有未显式指定超时的相机会话**一个统一上限（默认 3 分钟），
 * 到点就**剥夺**：回调里释放会话/设备（调用方负责真正 close + 结束界面）。
 *
 * 设计取向：
 *  · 计时是**单调时钟**（[SystemClock.elapsedRealtime]），不受系统时间调整影响；
 *  · 只有**当前**那个会话有效（每次 [start] 递增 token，旧的 [stop]/超时回调一律作废）——
 *    这样"快速开关相机"不会因为旧定时器串台而误杀新会话；
 *  · 超时动作在**主线程**执行（相机 API 与界面都在主线程，避免额外并发）；
 *  · 显式设置更长的超时是允许的（[start] 的 timeoutMs），"未设置"才走默认值。
 */
object CameraOccupancyGuard {

    private const val TAG = "CameraGuard"

    /** 未显式指定超时的相机会话上限：3 分钟 */
    const val DEFAULT_TIMEOUT_MS: Long = 3 * 60 * 1000L

    private val handler = Handler(Looper.getMainLooper())

    private var token: Int = 0
    private var startedAtMs: Long = 0
    private var deadlineMs: Long = 0
    private var tag: String = ""
    private var pending: (() -> Unit)? = null

    /** 会话是否正在计时（供界面显示剩余时间用） */
    val isRunning: Boolean get() = pending != null

    /** 距超时还剩多少毫秒（未计时返回 0） */
    fun remainingMs(): Long {
        val deadline = deadlineMs
        if (pending == null || deadline == 0L) return 0
        return (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    }

    /**
     * 开始计时。
     * @param tag 会话标识（日志用，例如 "record"/"probe"）
     * @param timeoutMs 显式超时；不传即用 [DEFAULT_TIMEOUT_MS]
     * @param onTimeout 到点回调（主线程）——**调用方必须在里面真正释放相机**
     */
    fun start(tag: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, onTimeout: () -> Unit) {
        stop()
        val myToken = ++token
        this.tag = tag
        startedAtMs = SystemClock.elapsedRealtime()
        deadlineMs = startedAtMs + timeoutMs
        pending = onTimeout
        Log.i(TAG, "相机占用计时开始：$tag，上限 ${timeoutMs / 1000}s")
        handler.postDelayed({
            if (myToken != token || pending == null) return@postDelayed
            pending = null
            deadlineMs = 0
            val held = SystemClock.elapsedRealtime() - startedAtMs
            Log.w(TAG, "相机占用超时（$tag 已持 ${held / 1000}s，上限 ${timeoutMs / 1000}s）⇒ 剥夺并释放")
            runCatching { onTimeout() }
                .onFailure { Log.w(TAG, "超时释放动作抛异常：${it.message}") }
        }, timeoutMs)
    }

    /** 结束计时（相机已释放时调用；重复调用安全） */
    fun stop() {
        if (pending != null) Log.i(TAG, "相机占用计时结束：$tag")
        token++
        pending = null
        deadlineMs = 0
        startedAtMs = 0
    }
}
