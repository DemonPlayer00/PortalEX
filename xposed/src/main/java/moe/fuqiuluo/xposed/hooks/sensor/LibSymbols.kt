package moe.fuqiuluo.xposed.hooks.sensor

import moe.fuqiuluo.xposed.utils.Logger
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 平台库内部符号解析（ELF + mini debug info）。
 *
 * 要 hook 的 `SensorHalWrapper::poll` 等函数在 `libsensorservice.so` 里是
 * **隐藏可见性**：`.dynsym` 里没有，dlsym 拿不到；文件也被 strip，没有普通 .symtab。
 * 平台库默认带 **mini debug info**（`.gnu_debugdata`：xz 压缩的一份只含
 * .symtab/.strtab 的 ELF），它是随**该机型固件**一起构建的，所以按名字查表得到的
 * 地址对当前设备永远精确——比按字节特征扫代码段稳得多，也不必为每个 ROM 写偏移表。
 *
 * 为什么在 Java 侧做：解 xz 需要一个解压器。本机 `/system/lib64/liblzma.so` 是
 * **7-Zip LZMA SDK**，不提供 xz-utils 的 `lzma_stream_buffer_decode`，系统里也没有
 * 别的库导出 xz 解码符号（实测扫过 system / system_ext / art apex）——原生侧无库可用。
 * 纯 Java 的 `org.tukaani:xz` 随模块 dex 一起进入 system_server，绕开这个依赖问题，
 * 也让这段逻辑能被日志完整观察。原生侧因此只剩"改指针 + 造事件"。
 *
 * 安全性：解析结果只被用来**核对**——原生层只在"该地址处确实存着这个函数指针"时才改写
 * （见 portal_sensor.c），所以解析错了不会写坏任何东西，最多是这个功能不生效。
 */
internal object LibSymbols {

    private const val SYM_AIDL_POLL = "_ZN7android20AidlSensorHalWrapper4pollEP15sensors_event_tm"
    private const val SYM_AIDL_FMQ = "_ZN7android20AidlSensorHalWrapper7pollFmqEP15sensors_event_tm"
    private const val SYM_HIDL_POLL = "_ZN7android20HidlSensorHalWrapper4pollEP15sensors_event_tm"
    private const val SYM_HIDL_FMQ = "_ZN7android20HidlSensorHalWrapper7pollFmqEP15sensors_event_tm"

    /**
     * 「应用期望频率」的入口（可选）：`SensorEventConnection::enableDisable(int, bool, int64, int64, int)`
     * —— 应用注册传感器时的采样周期只在这一层可见（Java 侧只是转发）。详见 portal_sensor.c。
     */
    private const val SYM_ENABLE_DISABLE =
        "_ZN7android13SensorService21SensorEventConnection13enableDisableEiblli"

    /** SHT_DYNSYM：导出符号表。本路径用不到它（隐藏符号在 mini debug info 里），
     *  能力保留给"需要读导出表"的场合。 */
    private const val SHT_DYNSYM_TYPE = 11

    private const val SEC_DEBUGDATA = ".gnu_debugdata"
    private const val SEC_DATA_REL_RO = ".data.rel.ro"

    /** 解析结果：偏移为 0 表示没找到（原生层据此跳过该槽位） */
    class Resolved(
        val libPath: String,
        val pollAidl: Long,
        val fmqAidl: Long,
        val pollHidl: Long,
        val fmqHidl: Long,
        val relroAddr: Long,
        val relroSize: Long,
        /** 采样率观测入口（0 = 本 ROM 没有 / 没解析到，只是拿不到频率，不影响注入） */
        val enableDisable: Long = 0L
    ) {
        val usable: Boolean
            get() = pollAidl != 0L || fmqAidl != 0L || pollHidl != 0L || fmqHidl != 0L

        /** 交给原生层的偏移数组（顺序与 BinderSensorNative.install 约定一致） */
        fun toOffsets(): LongArray {
            // 按**索引契约**组装（见 InstallOffsets）：不要在别处平铺这个数组，
            // 顺序错了 native 会读到别的字段（不崩，只是功能静默失效）。
            val out = LongArray(InstallOffsets.COUNT)
            out[InstallOffsets.RELRO_ADDR] = relroAddr
            out[InstallOffsets.RELRO_SIZE] = relroSize
            out[InstallOffsets.POLL_AIDL] = pollAidl
            out[InstallOffsets.POLL_FMQ_AIDL] = fmqAidl
            out[InstallOffsets.POLL_HIDL] = pollHidl
            out[InstallOffsets.POLL_FMQ_HIDL] = fmqHidl
            out[InstallOffsets.ENABLE_DISABLE] = enableDisable
            check(out.size == InstallOffsets.COUNT) { "offsets 契约长度不符：${out.size}" }
            return out
        }

        override fun toString(): String =
            "pollA=0x${pollAidl.toString(16)} fmqA=0x${fmqAidl.toString(16)} " +
                    "pollH=0x${pollHidl.toString(16)} fmqH=0x${fmqHidl.toString(16)} " +
                    "en=0x${enableDisable.toString(16)} " +
                    "relro=0x${relroAddr.toString(16)}+0x${relroSize.toString(16)} @$libPath"
    }

    @Volatile private var cached: Resolved? = null
    @Volatile private var failed = false

    /** 解析（进程内缓存一次；失败后不再重试，避免反复做几百 KB 的 IO） */
    fun resolve(): Resolved? {
        cached?.let { return it }
        if (failed) return null
        synchronized(this) {
            cached?.let { return it }
            if (failed) return null
            val r = runCatching { doResolve() }
                .onFailure { Logger.error("LibSymbols: resolve failed: ${it.message}", it) }
                .getOrNull()
            if (r == null || !r.usable) {
                failed = true
                Logger.error("LibSymbols: cannot resolve sensor HAL wrapper symbols")
                return null
            }
            Logger.info("LibSymbols: $r")
            cached = r
            return r
        }
    }

    private fun doResolve(): Resolved? {
        val path = locateLibrary() ?: run {
            Logger.error("LibSymbols: libsensorservice.so not loaded in this process")
            return null
        }
        val elf = ElfFile.of(File(path))
        val relro = elf.section(SEC_DATA_REL_RO)
        val dd = elf.section(SEC_DEBUGDATA) ?: run {
            Logger.error("LibSymbols: $path has no $SEC_DEBUGDATA (unsupported ROM)")
            return null
        }
        val plain = XZInputStream(ByteArrayInputStream(elf.read(dd))).use { it.readBytes() }
        Logger.info("LibSymbols: mini debug info ${dd.size} -> ${plain.size} bytes")
        val syms = ElfFile.of(plain)
        val resolved = Resolved(
            libPath = path,
            pollAidl = syms.symbolValue(SYM_AIDL_POLL),
            fmqAidl = syms.symbolValue(SYM_AIDL_FMQ),
            pollHidl = syms.symbolValue(SYM_HIDL_POLL),
            fmqHidl = syms.symbolValue(SYM_HIDL_FMQ),
            relroAddr = relro?.addr ?: 0L,
            relroSize = relro?.size ?: 0L,
            enableDisable = syms.symbolValue(SYM_ENABLE_DISABLE)
        )
        return resolved
    }

    /** 从 /proc/self/maps 找当前进程里 libsensorservice.so 的真实路径 */
    private fun locateLibrary(): String? = runCatching {
        File("/proc/self/maps").readLines().firstNotNullOfOrNull { line ->
            val idx = line.indexOf('/')
            if (idx < 0) return@firstNotNullOfOrNull null
            val p = line.substring(idx).trim()
            if (p.endsWith("/libsensorservice.so")) p else null
        }
    }.getOrNull()

    // ------------------------------------------------------------------
    // 极简 ELF64 读取（只读节表与符号表）
    // ------------------------------------------------------------------

    private class Section(
        val name: String,
        val type: Int,
        val addr: Long,
        val offset: Long,
        val size: Long,
        val link: Int
    )

    private class ElfFile private constructor(private val buf: ByteBuffer) {

        private val sections: List<Section> by lazy { parseSections() }

        fun section(name: String): Section? = sections.firstOrNull { it.name == name }

        fun read(s: Section): ByteArray {
            val out = ByteArray(s.size.toInt())
            val dup = buf.duplicate()
            dup.position(s.offset.toInt())
            dup.get(out)
            return out
        }

        /** 在 SHT_SYMTAB 里按名字取 st_value（= 链接期地址偏移） */
        fun symbolValue(mangled: String): Long = symbolValue(mangled, SHT_SYMTAB)

        /** 按节类型查符号：SHT_SYMTAB(2) = mini debug info，SHT_DYNSYM(11) = 导出符号表 */
        fun symbolValue(mangled: String, sectionType: Int): Long {
            val symtab = sections.firstOrNull { it.type == sectionType } ?: return 0L
            val strtab = sections.getOrNull(symtab.link) ?: return 0L
            val names = read(strtab)
            val count = (symtab.size / SYM_ENT_SIZE).toInt()
            // 注意：不能用 buf.duplicate()——duplicate() 会把字节序重置回 BIG_ENDIAN
            // （Android 与 JDK 的实现都如此），绝对读取会变成垃圾值。
            // buf 上的绝对 get 不改变 position，直接用它。
            for (i in 0 until count) {
                val base = (symtab.offset + i.toLong() * SYM_ENT_SIZE).toInt()
                val nameOff = buf.getInt(base)
                if (nameOff <= 0 || nameOff >= names.size) continue
                val value = buf.getLong(base + 8)
                if (value == 0L) continue
                if (nameAt(names, nameOff) == mangled) return value
            }
            return 0L
        }

        private fun nameAt(bytes: ByteArray, offset: Int): String {
            var end = offset
            while (end < bytes.size && bytes[end] != 0.toByte()) end++
            return String(bytes, offset, end - offset, Charsets.UTF_8)
        }

        private fun parseSections(): List<Section> {
            if (buf.limit() < 0x40) return emptyList()
            if (buf.get(0) != 0x7f.toByte() || buf.get(1) != 'E'.code.toByte()) return emptyList()
            val shoff = buf.getLong(0x28)
            val shentsize = buf.getShort(0x3a).toInt() and 0xffff
            val shnum = buf.getShort(0x3c).toInt() and 0xffff
            val shstrndx = buf.getShort(0x3e).toInt() and 0xffff
            if (shoff <= 0 || shnum <= 0 || shentsize < 0x40) return emptyList()
            fun rawAt(i: Int): Section {
                val b = (shoff + i.toLong() * shentsize).toInt()
                return Section(
                    name = "",
                    type = buf.getInt(b + 4),
                    addr = buf.getLong(b + 0x10),
                    offset = buf.getLong(b + 0x18),
                    size = buf.getLong(b + 0x20),
                    link = buf.getInt(b + 0x28)
                )
            }
            val raw = (0 until shnum).map { rawAt(it) }
            val shstr = raw.getOrNull(shstrndx) ?: return raw
            val names = read(shstr)
            return (0 until shnum).map { i ->
                val b = (shoff + i.toLong() * shentsize).toInt()
                val s = raw[i]
                Section(nameAt(names, buf.getInt(b)), s.type, s.addr, s.offset, s.size, s.link)
            }
        }

        companion object {
            private const val SHT_SYMTAB = 2
            private const val SYM_ENT_SIZE = 24

            fun of(file: File): ElfFile {
                val buf = FileChannel.open(file.toPath()).use { ch ->
                    val b = ByteBuffer.allocate(ch.size().toInt()).order(ByteOrder.LITTLE_ENDIAN)
                    while (b.hasRemaining()) if (ch.read(b) < 0) break
                    b.flip()
                    b
                }
                return ElfFile(buf)
            }

            fun of(bytes: ByteArray): ElfFile =
                ElfFile(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        }
    }
}
