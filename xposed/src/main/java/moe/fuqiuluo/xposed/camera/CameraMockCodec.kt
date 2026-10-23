package moe.fuqiuluo.xposed.camera

/**
 * 清单的**编解码**（手写 JSON，无第三方依赖）。
 *
 * 为什么不用现成 JSON 库：这份代码要跑在**每个被注入的目标应用进程**里，
 * 而 `:xposed` 的 dex 会随手一起进那些进程 —— 为一个几十行的清单结构拖进
 * 一整个 JSON 实现不划算；而且 `org.json` 在 JVM 单测里是 android.jar 桩
 * （调用即抛），没法给编解码写回归测试。
 *
 * 约定（两侧都必须遵守）：
 *  · **写者只有 app 侧**（[encode]），读侧（[decode]）必须对任何畸形输入
 *    返回 `null` 或关闭态清单 —— 宁可"这次不拦截"，也不能因为清单坏了去崩目标应用。
 *  · 数字：整数值写成整数（不写 `1.0`），小数按 [Double.toString] 原样写
 *    （可精确往返，含指数形式）；解析时整数收成 [Long]，其余收成 [Double]。
 */
internal object MiniJson {

    /** 序列化：Map / List / String / Number / Boolean / null 之外的类型一律按字符串写 */
    fun write(value: Any?): String {
        val sb = StringBuilder()
        writeTo(sb, value)
        return sb.toString()
    }

    private fun writeTo(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Float -> sb.append(numberToString(value.toDouble()))
            is Double -> sb.append(numberToString(value))
            is Number -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k?.toString() ?: "")
                    sb.append(':')
                    writeTo(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, v)
                }
                sb.append(']')
            }
            else -> writeString(sb, value.toString())
        }
    }

    /** 整数值的 Double 写成 `3` 而不是 `3.0`：清单肉眼可读，也省几个字节 */
    private fun numberToString(d: Double): String =
        if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString()
        else d.toString()

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    // 其余控制字符转义；中文/emoji 原样输出（UTF-8 传输，无需 \u 转义）
                    if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code))
                    else sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    /** 解析。任何语法错误返回 null（调用方按"清单不可用"处理） */
    fun parse(text: String): Any? =
        runCatching { Parser(text).parseDocument() }.getOrNull()

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            skipWs()
            val v = parseValue()
            skipWs()
            if (i != s.length) error("trailing content at $i")
            return v
        }

        private fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) error("unexpected end")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect("{")
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(":")
                map[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> error("expected , or } at $i")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect("[")
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> error("expected , or ] at $i")
                }
            }
        }

        private fun parseString(): String {
            expect("\"")
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) error("unterminated string")
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) error("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) error("bad \\u")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> error("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = i
            if (peek() == '-' || peek() == '+') i++
            var integral = true
            while (i < s.length) {
                val c = s[i]
                if (c in '0'..'9') { i++; continue }
                if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { integral = false; i++; continue }
                break
            }
            if (i == start) error("expected value at $i")
            val raw = s.substring(start, i)
            return if (integral) raw.toLongOrNull() ?: raw.toDouble() else raw.toDouble()
        }

        private fun peek(): Char = if (i < s.length) s[i] else error("unexpected end")

        private fun expect(token: String) {
            if (!s.startsWith(token, i)) error("expected $token at $i")
            i += token.length
        }

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        private fun error(msg: String): Nothing = throw IllegalArgumentException(msg)
    }
}

/**
 * 清单 ⇄ JSON。字段名即协议，**改动等于跨进程协议变更**：
 * app 与模块是同一个 APK，一起升级，因此不做向后兼容，但**读侧必须容错**。
 */
object CameraMockCodec {

    private const val VERSION = 1

    private const val K_VERSION = "version"
    private const val K_ENABLED = "enabled"
    private const val K_TAG_FILTER = "tagFilter"
    private const val K_TARGETS = "targets"
    private const val K_UPDATED_AT = "updatedAt"
    private const val K_POINTS = "points"
    private const val K_ID = "id"
    private const val K_NAME = "name"
    private const val K_LAT = "lat"
    private const val K_LON = "lon"
    private const val K_VIDEOS = "videos"
    private const val K_FILE = "file"
    private const val K_DURATION = "durationMs"
    private const val K_TAGS = "tags"
    private const val K_ADDED_AT = "addedAt"
    private const val K_RECORDED_AT = "recordedAt"
    private const val K_TRACK = "track"
    private const val K_T = "t"
    private const val K_TP_LAT = "lat"
    private const val K_TP_LON = "lon"

    fun encode(manifest: CameraMockManifest): String {
        val root = LinkedHashMap<String, Any?>()
        root[K_VERSION] = VERSION
        root[K_ENABLED] = manifest.enabled
        root[K_TAG_FILTER] = CameraTags.sanitize(manifest.tagFilter)
        root[K_TARGETS] = manifest.targets.toList()
        root[K_UPDATED_AT] = manifest.updatedAt
        root[K_POINTS] = manifest.points.map { pointToMap(it) }
        return MiniJson.write(root)
    }

    /** 只编/解"视频点数组"：app 侧把库存在偏好里时用它（开关与筛选另存简单类型，见 CameraMockStore） */
    fun encodePoints(points: List<CameraVideoPoint>): String =
        MiniJson.write(points.map { pointToMap(it) })

    fun decodePoints(text: String): List<CameraVideoPoint> {
        val list = MiniJson.parse(text) as? List<*> ?: return emptyList()
        return list.mapNotNull { pointFromMap(it as? Map<*, *> ?: return@mapNotNull null) }
    }

    private fun pointToMap(p: CameraVideoPoint): Map<String, Any?> = linkedMapOf(
        K_ID to p.id,
        K_NAME to p.name,
        K_LAT to p.lat,
        K_LON to p.lon,
        K_VIDEOS to p.videos.map { v ->
            linkedMapOf<String, Any?>(
                K_ID to v.id,
                K_NAME to v.name,
                K_FILE to v.file,
                K_DURATION to v.durationMs,
                K_TAGS to CameraTags.sanitize(v.tags),
                K_ADDED_AT to v.addedAt,
                K_RECORDED_AT to v.recordedAt,
                K_TRACK to v.track.map { tp ->
                    linkedMapOf<String, Any?>(
                        K_T to tp.tMs,
                        K_TP_LAT to tp.lat,
                        K_TP_LON to tp.lon
                    )
                }
            )
        }
    )

    /** 单个视频点：缺 id/坐标/名字则返回 null（该条目被丢弃，不影响其它条目） */
    private fun pointFromMap(p: Map<*, *>): CameraVideoPoint? {
        val id = (p[K_ID] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val lat = (p[K_LAT] as? Number)?.toDouble() ?: return null
        val lon = (p[K_LON] as? Number)?.toDouble() ?: return null
        val videos = ArrayList<CameraVideo>()
        (p[K_VIDEOS] as? List<*>)?.forEach { rawVideo ->
            val v = rawVideo as? Map<*, *> ?: return@forEach
            val vid = (v[K_ID] as? String)?.takeIf { it.isNotBlank() } ?: return@forEach
            val file = (v[K_FILE] as? String)?.takeIf { it.isNotBlank() } ?: return@forEach
            videos.add(
                CameraVideo(
                    id = vid,
                    name = (v[K_NAME] as? String)?.takeIf { it.isNotBlank() } ?: file,
                    file = file,
                    durationMs = ((v[K_DURATION] as? Number)?.toLong() ?: 0L).coerceAtLeast(0L),
                    tags = CameraTags.sanitize(strings(v[K_TAGS])),
                    addedAt = (v[K_ADDED_AT] as? Number)?.toLong() ?: 0L,
                    recordedAt = (v[K_RECORDED_AT] as? Number)?.toLong() ?: 0L,
                    track = CameraTags.sanitizeTrack(
                        (v[K_TRACK] as? List<*>)?.mapNotNull { raw ->
                            val m = raw as? Map<*, *> ?: return@mapNotNull null
                            val t = (m[K_T] as? Number)?.toLong() ?: return@mapNotNull null
                            val lat = (m[K_TP_LAT] as? Number)?.toDouble() ?: return@mapNotNull null
                            val lon = (m[K_TP_LON] as? Number)?.toDouble() ?: return@mapNotNull null
                            CameraVideoTrackPoint(t, lat, lon)
                        }
                    )
                )
            )
        }
        return CameraVideoPoint(
            id = id,
            name = (p[K_NAME] as? String)?.takeIf { it.isNotBlank() } ?: id,
            lat = lat,
            lon = lon,
            videos = videos
        )
    }

    /**
     * 解码整份清单。以下任一情况返回 **null**（调用方保持上一次的清单/关闭态）：
     * 语法错误、根不是对象、缺少 `enabled`。单个点/视频条目坏了只丢该条目 ——
     * 坏一个视频不该让其余点位一起失效。
     */
    fun decode(text: String): CameraMockManifest? {
        val root = MiniJson.parse(text) as? Map<*, *> ?: return null
        val enabled = root[K_ENABLED] as? Boolean ?: return null
        val filter = CameraTags.sanitize(strings(root[K_TAG_FILTER]))
        val targets = strings(root[K_TARGETS]).map { it.trim() }.filter { it.isNotEmpty() }
        val updatedAt = (root[K_UPDATED_AT] as? Number)?.toLong() ?: 0L
        val points = (root[K_POINTS] as? List<*>)
            ?.mapNotNull { pointFromMap(it as? Map<*, *> ?: return@mapNotNull null) }
            ?: emptyList()
        return CameraMockManifest(enabled, filter, points, targets, updatedAt)
    }


    private fun strings(value: Any?): List<String> =
        (value as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
}
