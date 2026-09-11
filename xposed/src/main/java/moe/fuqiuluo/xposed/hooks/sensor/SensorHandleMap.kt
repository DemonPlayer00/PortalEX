package moe.fuqiuluo.xposed.hooks.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import moe.fuqiuluo.xposed.utils.BinderUtils
import moe.fuqiuluo.xposed.utils.Logger

/**
 * 传感器类型 → handle 映射（**不依赖真实事件**）。
 *
 * 为什么需要它：注入事件必须知道每个类型对应的 handle。原先只在真实事件到达时才登记，
 * 于是「整机从未产生过该类型事件」的传感器永远学不到 —— 最典型的是**步数计数器 /
 * 步数检测器**：它们是 on-change 传感器，手机不走路就没有事件，结果是"模拟走路"时
 * 步频根本推不出去（要真机先走两步才学会）。这与「完全隔离」的要求相冲突。
 *
 * 这里改用框架自己的表：system_server 拿得到系统 Context → `SensorManager`
 * → `getSensorList(TYPE_ALL)` → 每个 `Sensor` 的（隐藏）`getHandle()`。
 * 这是应用注册传感器时走的同一条链，**与 HAL 是否在出数据无关**，也不需要对平台内部
 * 结构体做任何布局假设（之前尝试遍历 HAL 的 `getSensorsList()` 正是栽在元素大小上）。
 *
 * 拿不到就返回 null：调用方保留"从真实事件学习"的兜底路径，行为不变。
 */
internal object SensorHandleMap {

    /** @return 三元组序列 [type, handle, flags, ...]；不可用时为 null */
    fun collect(): LongArray? {
        return try {
            val ctx = BinderUtils.getSystemContext() ?: run {
                Logger.warn("SensorHandleMap: no system context")
                return null
            }
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: run {
                Logger.warn("SensorHandleMap: no SensorManager")
                return null
            }
            val sensors = sm.getSensorList(Sensor.TYPE_ALL) ?: return null
            val out = ArrayList<Long>(sensors.size * 3)
            for (s in sensors) {
                val handle = readInt(s, "getHandle", "mHandle") ?: continue
                if (handle <= 0) continue
                val flags = readInt(s, null, "mFlags") ?: 0
                out.add(s.type.toLong())
                out.add(handle.toLong())
                out.add(flags.toLong())
            }
            if (out.isEmpty()) null else out.toLongArray()
        } catch (t: Throwable) {
            Logger.error("SensorHandleMap: collect failed: ${t.message}", t)
            null
        }
    }

    /**
     * 厂商私有传感器（type >= `SENSOR_TYPE_DEVICE_PRIVATE_BASE` = 0x10000）清单。
     *
     * 为什么要单独看它们：应用拿步频/活动的路径不止公版 `TYPE_STEP_COUNTER`，
     * 厂商还塞了一批私有类型（本机如 `pedometer_minute` 33171034、
     * `oplus_activity_recognition` 33171037）。这些**也在同一个事件出口上**，
     * 但不在本模块的接管集合里 —— 先监控，判断是否存在"公版在走、私版不动"的不自洽。
     *
     * @return "33171034:pedometer_minute:0x1f4 ..."；不可用时为 null
     */
    fun privateTypes(): String? {
        return try {
            val ctx = BinderUtils.getSystemContext() ?: return null
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
            val sensors = sm.getSensorList(Sensor.TYPE_ALL) ?: return null
            val sb = StringBuilder()
            for (s in sensors) {
                if (s.type < 0x10000) continue
                val handle = readInt(s, "getHandle", "mHandle") ?: continue
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s.type).append(':').append(s.name?.trim()).append(":0x")
                    .append(handle.toString(16))
            }
            if (sb.isEmpty()) null else sb.toString()
        } catch (t: Throwable) {
            Logger.error("SensorHandleMap: privateTypes failed: ${t.message}", t)
            null
        }
    }

    /** 先试隐藏方法（@hide getHandle()），再试同名字段——绕过不同版本的差异 */
    private fun readInt(sensor: Sensor, method: String?, field: String): Int? {
        if (method != null) {
            runCatching {
                val m = Sensor::class.java.getMethod(method)
                m.isAccessible = true
                (m.invoke(sensor) as? Int)?.let { return it }
            }
        }
        runCatching {
            val f = Sensor::class.java.getDeclaredField(field)
            f.isAccessible = true
            return f.getInt(sensor)
        }
        return null
    }
}
