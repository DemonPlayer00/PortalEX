package moe.fuqiuluo.xposed.utils

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 虚拟世界的**纯计算**部分（不读任何全局状态、不碰 Android API）。
 *
 * 抽出来的理由很实际：这些函数以前长在 [FakeLoc]（594 行、三十多个可变全局）里，
 * 想给它们写单测就得先把整个 FakeLoc 拖起来；而它们本身只是球面几何与确定性随机 ——
 * 放进独立单元后可以**在 JVM 上直接测**（见 `WorldMathTest`），也就有了回归保护。
 *
 * 约定：这里只放"输入 → 输出"的纯函数；任何需要读写世界状态的逻辑留在 [FakeLoc]。
 */
object WorldMath {

    /** 地球半径（米）：与 FakeLoc 的历史取值一致，改它会改变所有位移/速度推算 */
    private const val EARTH_RADIUS_M = 6371000.0

    /** 两点球面距离（米）。haversine 公式。 */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = EARTH_RADIUS_M
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return radius * c
    }

    /** A → B 的初始方位角（度，正北为 0，顺时针）。 */
    fun calculateBearing(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val lat1 = Math.toRadians(latA)
        val lon1 = Math.toRadians(lonA)
        val lat2 = Math.toRadians(latB)
        val lon2 = Math.toRadians(lonB)

        val deltaLon = lon2 - lon1

        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)

        var bearing = Math.toDegrees(atan2(y, x))
        bearing = (bearing + 360) % 360 // 标准化到 0-360 度

        return bearing
    }

    /**
     * 卫星快照：**按时间桶确定**（桶 = 1 秒）—— 纯函数，同一 (桶, 下限, 上限) 必得同一份。
     *
     * 为什么必须确定性：extras 的改写发生在多个进程（system_server、fused provider、
     * 各家 NLP SDK 进程），快照对象无法跨进程共享；做成「时间桶 → 固定随机序列」的纯函数后，
     * **任何进程在同一秒内都得到同一份卫星数据**，与真机 1Hz 上报的物理事实一致。
     */
    fun gnssSnapshotForBucket(
        bucketSec: Long,
        minSatellites: Int,
        maxSatellites: Int,
    ): FakeLoc.GnssSnapshot {
        val rng = Random(bucketSec * 1_000_003L + minSatellites * 7919L + 0x5DEECE66DL)
        val svCount = rng.nextInt(minSatellites, maxSatellites + 1)
        val cn0s = DoubleArray(svCount) { 24.0 + rng.nextDouble() * 21.0 } // 24~45 dB-Hz（真机量级）
        return FakeLoc.GnssSnapshot(svCount, cn0s)
    }
}
