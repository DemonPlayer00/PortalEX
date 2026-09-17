package moe.fuqiuluo.portalex.service

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * **逐帧记录器**：把客户端实际收到的每一帧位置落到文件，供离线分析配速曲线。
 *
 * ## 为什么需要它（而不是靠 logcat）
 *
 * 配速尖峰这类问题只能靠"**逐帧时间序列**"定位，而 logcat 在这台设备上不合适：
 * 模块的 `enableDebugLog` 一开就是 ~800 行/秒（LSPosed 把每条都带一段前缀），
 * 逐帧记录活不过几十秒就被冲掉。文件没有这个上限，也不受 LSPosed 日志格式影响。
 *
 * ## 记录的是"客户端视角"
 *
 * 写进文件的是 [PortalLocationClient] 从 `LocationManager` 收到的那一份 Location
 * —— 与任何第三方应用收到的是**同一批对象**（同一 tick 投给所有注册）。
 * 所以这份数据能回答"应用看到的数据里到底有没有周期性尖峰"，而不必猜它的算法。
 *
 * ## 门禁
 *
 * 只在设置页「调试日志」（`debug`）打开时记录，默认关闭：不改变任何既有行为，
 * 也不给普通使用留下持续写文件的负担。
 */
object FrameRecorder {

    private const val TAG = "FrameRecorder"

    /** 外部私有目录下的文件名：`Android/data/<pkg>/files/frames.csv` */
    private const val FILE_NAME = "frames.csv"

    /** 攒够这么多行就落盘一次（掉电/强杀不会把整段记录带走） */
    private const val FLUSH_EVERY_ROWS = 20

    @Volatile
    var enabled: Boolean = false
        private set

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var target: File? = null
    private var rowsSinceFlush = 0
    private var rowCount = 0L

    /** 当前（或最近一次）记录文件路径；未开始记录时为 null */
    fun filePath(): String? = target?.absolutePath

    fun rows(): Long = rowCount

    /**
     * 开始记录（覆盖旧文件）。重复调用是幂等的。
     *
     * 表头写带 `#` 的一行：分析脚本可以无脑跳过注释，同时留一份"列是什么"的现场证据。
     */
    fun start(context: Context) {
        synchronized(lock) {
            if (writer != null) return
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, FILE_NAME)
            val w = runCatching {
                if (!dir.exists()) dir.mkdirs()
                BufferedWriter(FileWriter(file, false))
            }.onFailure { Log.w(TAG, "记录文件不可写，逐帧记录未启用：${it.message}") }.getOrNull() ?: return
            runCatching {
                w.write("# et_ns,lat,lon,speed_mps,bearing_deg,accuracy_m,provider,time_ms")
                w.newLine()
            }
            writer = w
            target = file
            rowsSinceFlush = 0
            rowCount = 0L
            enabled = true
            Log.i(TAG, "逐帧记录已开始：${file.absolutePath}")
        }
    }

    fun stop() {
        synchronized(lock) {
            enabled = false
            val w = writer ?: return
            writer = null
            runCatching { w.flush() }
            runCatching { w.close() }
            Log.i(TAG, "逐帧记录已停止：共 $rowCount 行 → ${target?.absolutePath}")
        }
    }

    /**
     * 记一帧。**绝不允许抛异常**（同 [moe.fuqiuluo.xposed.utils.Logger] 的铁律）：
     * 这个调用点在每帧的位置投递路径上，写文件失败只能丢一行，不能连累交付。
     */
    fun record(fix: PortalLocationClient.Fix) {
        if (!enabled) return
        synchronized(lock) {
            val w = writer ?: return
            runCatching {
                w.write(
                    fix.elapsedRealtimeNanos.toString() + ',' +
                        fix.lat + ',' + fix.lon + ',' +
                        fix.speed + ',' + fix.bearing + ',' + fix.accuracy + ',' +
                        fix.provider + ',' + fix.timeMillis
                )
                w.newLine()
                rowCount += 1
                rowsSinceFlush += 1
                if (rowsSinceFlush >= FLUSH_EVERY_ROWS) {
                    w.flush()
                    rowsSinceFlush = 0
                }
            }.onFailure {
                Log.w(TAG, "写逐帧记录失败（丢一行）：${it.message}")
            }
        }
    }
}
