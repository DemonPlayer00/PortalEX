package moe.fuqiuluo.xposed.hooks

import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.MotionEngine
import moe.fuqiuluo.xposed.utils.StaminaRuntime

/**
 * **路线位置采样接口**：按"现在"计算一次当前应在的位置。
 *
 * 这是 `getCurrentLocation()`、命令通道 `GET_LOCATION` 与摄像头选片共用的唯一入口：
 *  · 只读取路线/推进状态，调用 [MotionEngine.positionAhead] 做纯推算；
 *  · **不写回** [FakeLoc]，不唤醒/调整 [MotionClock]，也不投递任何位置帧；
 *  · 因此高频调用（摄像头每次打开、应用反复取当前定位）不会改变其它位置分发的节奏。
 *
 * 路线未播放或尚未展开时返回当前镜像坐标，`sampledAhead=false`，让调用方保持原行为。
 */
object RoutePositionSampler {

    /**
     * @property sampledAhead true = 位置来自本次路线采样（路线正在播放）；
     *   false = 路线未播放，[lat]/[lon] 是当前镜像坐标。
     */
    data class Sample(
        val lat: Double,
        val lon: Double,
        val bearing: Double?,
        val sampledAhead: Boolean,
    )

    fun sample(): Sample {
        val ahead = MotionEngine.positionAhead(
            MotionClock.elapsedSinceBeatNanos() / 1e9,
            FakeLoc.speed,
            StaminaRuntime.multiplier(),
        )
        return if (ahead.moved) {
            Sample(ahead.lat, ahead.lon, ahead.bearing, sampledAhead = true)
        } else {
            Sample(FakeLoc.latitude, FakeLoc.longitude, null, sampledAhead = false)
        }
    }
}
