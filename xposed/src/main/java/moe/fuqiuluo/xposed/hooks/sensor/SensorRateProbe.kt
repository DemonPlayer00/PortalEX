package moe.fuqiuluo.xposed.hooks.sensor

import android.os.IBinder
import android.os.ParcelFileDescriptor
import moe.fuqiuluo.xposed.utils.BinderUtils
import moe.fuqiuluo.xposed.utils.Logger
import java.io.FileInputStream

/**
 * 「应用期望频率」观测（**只在 system_server 里使用**）。
 *
 * 为什么要它：应用调 `registerListener` 时请求的采样周期，Java 层看不到——它一路走到
 * 原生 `SensorEventConnection` 才落地。要"伪造得像"，就得知道每个传感器上被请求了多快
 * （现在我们的注入是固定内部栅格：加速度 50Hz、磁场 25Hz…，与应用的请求无关；实测框架
 * 也**不会**按应用请求节流我们注入的事件，所以"请求 5Hz 却收到 50Hz"本身就是破绽）。
 *
 * 两条互补的路（本类负责第 ②，第 ① 在原生层）：
 * ① 原生 `SensorEventConnection::enableDisable` vtable 槽（[BinderSensorNative.enableRequests]）：
 *    拿到**每个客户端、每次请求**的原始值 + 调用方 uid ⇒ 可归因到具体应用；
 * ② 框架自己的 `sensorservice` binder `dump()`（本类）：拿到**该传感器上被请求的全部周期 +
 *    框架采用值 `selected`**（就是 `dumpsys sensorservice` 里那份），零 hook、但不区分 pid。
 *
 * 语义提醒：① 是**原始请求**，② 的 `selected` 是**框架采用值**（中间经过 `capRates()`：
 * 无 `HIGH_SAMPLING_RATE_SENSORS` 的应用被压到 200Hz，以及厂商扩展的采样周期调整）。
 * 要"像"，应以 ② 的 `selected` 为准，用 ① 做归因。
 *
 * 安全边界：只用公开 binder 接口 `IBinder.dump(fd, args)`（调用方是 system_server，天然持有
 * DUMP 权限），解析失败/权限不足一律降级为一行说明，绝不影响注入本身。
 */
internal object SensorRateProbe {

    /** dump 有点重（本机约 40KB 文本），缓存住，别让 UI 每秒都去拉一次 */
    private const val CACHE_NANOS = 2_000_000_000L

    /** 只展示我们接管的这些传感器（框架 dump 里 42 个传感器全打出来没人看） */
    private const val MAX_ENTRIES = 6

    @Volatile private var cachedAtNanos = 0L
    @Volatile private var cached = "（未采集）"
    @Volatile private var lastError = ""

    fun status(): String {
        val now = System.nanoTime()
        if (now - cachedAtNanos < CACHE_NANOS) return cached
        cachedAtNanos = now
        cached = runCatching { collect() }
            .onFailure { Logger.debug("SensorRateProbe: ${it.message}") }
            .getOrElse { "采集失败: ${it::class.java.simpleName}: ${it.message}" }
        return cached
    }

    // ------------------------------------------------------------------
    // 「按应用期望出数据」：把活跃表与采用速率灌给原生注入层
    // ------------------------------------------------------------------

    @Volatile private var cachedText: String? = null
    @Volatile private var cachedTextAtNanos = 0L

    /** 带缓存的 dump 文本（[status] 与 [pushHints] 共用；拉取失败时沿用旧缓存） */
    private fun dumpCached(): String? {
        val now = System.nanoTime()
        cachedText?.let { if (now - cachedTextAtNanos < CACHE_NANOS) return it }
        val fresh = dumpSensorService() ?: return cachedText
        cachedText = fresh
        cachedTextAtNanos = now
        return fresh
    }

    /**
     * 把「哪些传感器有人在订、框架采用的速率是多少」灌给原生注入层。
     *
     * 语义与真机对齐：HAL 按最快请求出力 ⇒ 我们只要"一个传感器一个速率"；
     * 没人订阅的类型由 [BinderSensorNative.clearChannelHints] 统一标成静默。
     *
     * **信息不足时什么都不做**（不是"先清了再说"）：clear 之后要靠 handle→type 表才能把
     * 活跃者灌回去，表不可用就等于**把所有栅格通道标成"没人订"而静默**，可真实事件此时
     * 仍在被压制（portal_sensor.c 的 post_process）⇒ 接管类型彻底没数据。
     * 这是 fail-silent，比"这一轮不更新"坏得多 —— 实测过的事故形态就是"数据凭空消失、
     * 而 Test 页一切正常"。
     */
    fun pushHints(): Boolean {
        val text = dumpCached() ?: return false
        val active = activeHandles(text)
        val byHandle = handleToType()
        // 清空前必须先确认"灌得回去"：拿不到类型表就放弃本轮更新，保持现状
        // （现状可能是"还没 hint 过"= 照旧出力，或上一轮的活跃集合 —— 都比全静默好）
        if (byHandle.isEmpty()) {
            Logger.debug("SensorRateProbe.pushHints: 传感器类型表不可用，跳过本轮（不清空通道）")
            return false
        }
        return runCatching {
            BinderSensorNative.clearChannelHints()
            var pushed = 0
            for ((handle, type) in byHandle) {
                val (selectedNs, batchNs) = active[handle] ?: continue
                BinderSensorNative.setChannelHint(type, selectedNs, batchNs, true)
                pushed++
            }
            pushed > 0
        }.onFailure { Logger.debug("SensorRateProbe.pushHints: ${it.message}") }.getOrDefault(false)
    }

    /**
     * dump 第一段（`Sensor Device:`）**只列有订阅者的传感器** ⇒ key 集合就是活跃 handle，
     * value 是框架采用值（ns；0 = 最快档/未指定 ⇒ 原生侧用内置默认栅格）。
     */
    private fun activeHandles(text: String): Map<Int, Pair<Long, Long>> {
        val out = HashMap<Int, Pair<Long, Long>>()
        for (line in text.lineSequence()) {
            val g = lineRe.find(line.trim()) ?: continue
            val handle = g.groupValues[1].removePrefix("0x").removePrefix("0X").toInt(16)
            val periodMs = g.groupValues[4].toDouble()
            val batchMs = g.groupValues[6].toDoubleOrNull() ?: 0.0
            out[handle] = (if (periodMs <= 0.0) 0L else (periodMs * 1_000_000.0).toLong()) to
                    (if (batchMs <= 0.0) 0L else (batchMs * 1_000_000.0).toLong())
        }
        return out
    }

    private fun collect(): String {
        val text = dumpCached()
        return if (text == null) {
            "框架: 不可用${if (lastError.isEmpty()) "" else "($lastError)"} | " +
                    clientRequests()
        } else {
            frameworkRates(text) + " | " + connections(text) + " | " + clientRequests()
        }
    }

    /**
     * 连接视角：dump 的 `Connection Number:` 段里有**每个客户端**的 uid/pid/进程名 + 它使能的
     * 传感器 handle —— 这是"哪个应用在要这个传感器"的权威答案（不依赖原生层能否取到 uid）。
     */
    private val connHeadRe = Regex("""Connection Number:\s*(\d+)""")
    private val connOwnerRe = Regex("""^\s*(\S+)\s*\|.*?uid (\d+) \| pid (\d+)""")
    private val connSensorRe = Regex("""0x([0-9a-fA-F]{8})\s*\|""")

    private fun connections(text: String): String {
        val byHandle = handleToType()
        data class Conn(val name: String, val uid: Int, var handles: MutableList<Int>)

        val conns = ArrayList<Conn>()
        var cur: Conn? = null
        for (line in text.lineSequence()) {
            if (connHeadRe.containsMatchIn(line)) {
                cur = null
                continue
            }
            val owner = connOwnerRe.find(line)
            if (owner != null && cur == null) {
                cur = Conn(owner.groupValues[1], owner.groupValues[2].toInt(), ArrayList())
                conns.add(cur)
                continue
            }
            val c = cur ?: continue
            connSensorRe.find(line)?.let { c.handles.add(it.groupValues[1].toInt(16)) } /* 已剥前缀的 8 位十六进制 */
        }
        if (conns.isEmpty()) return "客户端连接: 无"

        val wanted = byHandle.keys
        val interesting = conns.filter { c -> c.handles.any { wanted.isEmpty() || it in wanted } }
        if (interesting.isEmpty()) return "客户端连接: 无（没人订我们接管的传感器）"
        return "客户端连接: " + interesting.take(MAX_ENTRIES).joinToString(" ") { c ->
            val hs = c.handles
                .filter { wanted.isEmpty() || it in wanted }
                .sorted()
                .joinToString(",") { h -> "${byHandle[h]?.let { "t$it:" } ?: ""}0x${h.toString(16)}" }
            "${c.name.substringAfterLast('.')}(uid=${c.uid})→$hs"
        }
    }

    private fun handleToType(): Map<Int, Int> = runCatching {
        val triples = SensorHandleMap.collect() ?: return@runCatching emptyMap()
        val m = HashMap<Int, Int>()
        var i = 0
        while (i + 2 < triples.size) {
            m[triples[i + 1].toInt()] = triples[i].toInt()
            i += 3
        }
        m
    }.getOrDefault(emptyMap())

    // ------------------------------------------------------------------
    // ② 框架视角：sensorservice 的 dump 文本
    // ------------------------------------------------------------------

    /**
     * 形如 `0x000000bf) active-count = 4; sampling_period(ms) = {200.0, 66.7, 200.0, 66.7},
     * selected = 66.67 ms; batching_period(ms) = {...}, selected = 0.00 ms`
     */
    private val lineRe = Regex(
        """^(0x[0-9a-fA-F]+)\)\s+active-count = (\d+);\s+sampling_period\(ms\) = \{([^}]*)\},\s+selected = ([\d.]+) ms(?:;\s+batching_period\(ms\) = \{([^}]*)\},\s+selected = ([\d.]+) ms)?"""
    )

    private fun frameworkRates(text: String): String {
        // 只保留我们接管了的 handle（type→handle 由框架传感器表播种）
        val byHandle = handleToType()

        val parts = ArrayList<String>()
        for (line in text.lineSequence()) {
            val m = lineRe.find(line.trim()) ?: continue
            val handle = m.groupValues[1].removePrefix("0x").removePrefix("0X").toInt(16)
            if (byHandle.isNotEmpty() && !byHandle.containsKey(handle)) continue
            val type = byHandle[handle]
            val requested = m.groupValues[3].split(',')
                .map { it.trim().toDouble() }
                .filter { it > 0.0 }
                .distinct()
                .sorted()
                .joinToString("/") { fmtMs(it) }
            parts.add("${if (type != null) "t$type:" else ""}0x${handle.toString(16)}" +
                    "={$requested}sel=${fmtMs(m.groupValues[4].toDouble())}")
            if (parts.size >= MAX_ENTRIES) break
        }
        return if (parts.isEmpty()) {
            // 解析不到就把 dump 的规模与开头带出来 —— 原生/权限类失败一眼可辨（status 字符串是
            // 本机唯一可靠的诊断通道：原生日志在 logcat 里看不见）
            val head = text.replace('\n', '|').take(110)
            "框架: 无匹配（dump ${text.length} 字符: $head）"
        } else "框架(请求/采用): " + parts.joinToString(" ")
    }

    /** 让 `sensorservice` 把自己的状态 dump 到管道里再读回来（与 `dumpsys sensorservice` 同源）。 */
    private fun dumpSensorService(): String? {
        lastError = ""
        val binder = runCatching {
            val cls = Class.forName("android.os.ServiceManager")
            cls.getMethod("getService", String::class.java).invoke(null, "sensorservice") as? IBinder
        }.onFailure { lastError = "ServiceManager: ${it.message}" }.getOrNull()
        if (binder == null) {
            if (lastError.isEmpty()) lastError = "sensorservice 未注册"
            return null
        }
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: run {
            lastError = "createPipe 失败"
            return null
        }
        val read = pipe[0]
        val write = pipe[1]
        val out = StringBuilder()
        val reader = Thread({
            runCatching {
                FileInputStream(read.fileDescriptor).use { ins ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.append(String(buf, 0, n))
                    }
                }
            }.onFailure { lastError = "读 dump: ${it.message}" }
        }, "PortalSensorRateDump").apply {
            isDaemon = true
            start()
        }
        /*
         * **必须清调用身份**：这段代码是在"应用请求诊断状态"的调用链里跑的，不清身份时
         * 转发的 dump 会带着**应用的 uid**（实测 `Permission Denial: ... from pid=5830, uid=10467`），
         * 而 DUMP 权限只有系统侧有。清掉之后以 system_server 自己的身份发这次 binder 调用。
         */
        val token = runCatching { android.os.Binder.clearCallingIdentity() }.getOrNull()
        val ok = runCatching { binder.dump(write.fileDescriptor, arrayOf<String>()) }
            .onFailure { lastError = "dump 调用: ${it.message}" }
            .isSuccess
        if (token != null) runCatching { android.os.Binder.restoreCallingIdentity(token) }
        runCatching { write.close() }
        runCatching { reader.join(1500) }
        runCatching { read.close() }
        if (!ok) return null
        return out.toString().ifEmpty { lastError = "dump 为空"; null }
    }

    // ------------------------------------------------------------------
    // ① 客户端视角：原生层记下的 enableDisable 请求（带 uid → 包名）
    // ------------------------------------------------------------------

    private fun clientRequests(): String {
        val raw = runCatching { BinderSensorNative.enableRequests() }
            .getOrElse { return "客户端: 原生层不可用(${it.message})" }
        return "客户端(原始请求): " + annotateUids(raw)
    }

    /** 把 `uid=10467` 补成 `uid=10467(com.xxx)`，便于一眼看出是哪个应用要的。 */
    private fun annotateUids(raw: String): String {
        val pm = runCatching { BinderUtils.getSystemContext()?.packageManager }.getOrNull()
            ?: return raw
        return Regex("uid=(\\d+)").replace(raw) { m ->
            val uid = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            val pkgs = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
            val name = pkgs?.firstOrNull()?.substringAfterLast('.')
            if (name.isNullOrEmpty()) m.value else "uid=$uid($name)"
        }
    }

    private fun fmtMs(ms: Double): String =
        if (ms >= 100) "${ms.toInt()}" else "%.1f".format(ms)

    // 供 Test 页/日志用的完整首行说明（出错时便于定位）
    val failure: String? get() = lastError.ifEmpty { null }
}
