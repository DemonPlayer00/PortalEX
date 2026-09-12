package moe.fuqiuluo.portalex.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import moe.fuqiuluo.portalex.MainActivity
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.ext.keepAliveInBackground

/**
 * 会话期间的**后台保活服务**（设置项「后台保活」，默认开）。
 *
 * ## 为什么需要它（这次是有实测依据的）
 * App 是普通应用进程：退到后台就是 cached，会被 Android 的 **Cached Apps Freezer** 冻结。
 * 冻结期间运动循环（`MockServiceViewModel` 的协程）整段停摆 —— 真机实测到 25.6s / 25.8s /
 * 47.7s 的 tick 空洞，而 system_server 侧仍在按保活节奏推帧：**位置一动不动、帧内 vel≈0**，
 * 目标跑步应用看到的就是"原地不动"。
 *
 * 前台服务把本进程钉在 `FOREGROUND_SERVICE` 档（`dumpsys activity oom` 里是 `fgsl`），
 * **冻结器只冻 cached 进程**，所以只要这个服务在，循环就不会被冻；再配一个
 * `PARTIAL_WAKE_LOCK`，灭屏时 CPU 也不会挂起 —— 两条合起来才叫"稳定控制"。
 *
 * ## 生命周期（与模拟会话严格同寿）
 * · 会话开始（`MockServiceHelper.tryOpenMock` 成功）→ [start]；
 * · 会话结束（`tryCloseMock`）→ [stop]；
 * · 进程被杀后系统按 START_STICKY 拉起时，`onStartCommand` 会复查会话是否还在：
 *   不在就 `stopSelf()` —— 绝不允许"没人开会话、服务却常驻"。
 *
 * 关掉设置项时同样会 `stop`（App 侧开关与偏好一起生效）；关闭后行为与改造前一致：
 * 不占前台、不持锁，靠浮窗（如果有）维持 perceptible。
 */
class MockKeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "portalex_session"
        private const val CHANNEL_NAME = "模拟会话"
        private const val NOTIFICATION_ID = 0x50A1
        private const val WAKE_LOCK_TAG = "PortalEX:mockSession"

        /** 启动（幂等）。只在会话确实需要时由调用方触发。 */
        fun start(context: Context) {
            val intent = Intent(context, MockKeepAliveService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 停止（幂等）。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MockKeepAliveService::class.java)) }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Android 12+ 要求 startForeground 及时调用，否则会 ANR/异常
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统按 START_STICKY 拉起 / 会话已结束 / 用户关了开关 ⇒ 立刻退出，不做常驻幽灵
        val sessionRunning = runCatching { MockServiceHelper.isMockStart(locationManager()) }
            .getOrDefault(false)
        if (!sessionRunning || !applicationContext.keepAliveInBackground) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun locationManager(): LocationManager =
        getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_LOW：常驻但不打扰（不响、不弹）
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_my_location)
            .setContentTitle("PortalEX 模拟运行中")
            .setContentText("保持后台活跃，位置推进不受系统冻结影响")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tapIntent)
            .build()
    }

    /** partial wake lock：只保证 CPU 不挂起（不点屏、不影响省电策略的其它部分） */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }
}
