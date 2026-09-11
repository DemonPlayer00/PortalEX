package moe.fuqiuluo.xposed.utils

/**
 * 注入噪声档（Calibration 页可编辑）—— **逐轴 σ + 陀螺零偏**。
 *
 * 口径（本次设计定调）：
 *  - 噪声在校准时按轴采集**中位数 μ 与方差 σ²**；使用时按 **高斯分布 N(μ, σ²) 逐事件生成动态值**。
 *  - **中位数只对陀螺生效**：陀螺的零参考物理上就是 0，静止窗口的实测中位数就是它的真实零偏
 *    （PKG110 实测 z≈0.09 rad/s；旧实现里我们的陀螺零偏恒为 0，这是可判定的伪造痕迹）。
 *  - 加速度/重力/线性加速度/磁场的实测中位数里混着**手机姿态与环境地磁的直流分量**
 *    （平放时加速度计 z 的中位数就是 9.81），叠加会把模型打坏 ⇒ 它们的中位数**只作为统计参照**
 *    在 Calibration 页显示，不参与注入。
 *
 * profile 是一维 FloatArray，布局与原生 `VW_NOISE_*`（virtual_world.h）**逐项一致**：
 * ```
 * 0..2   陀螺 σ (x,y,z)      3..5   陀螺零偏 μ (x,y,z)   ← 唯一被注入的中位数，可为负
 * 6..8   加速度计 σ          9..11  重力 σ
 * 12..14 线性加速度 σ        15..17 磁场 σ
 * 18     方向角 σ            19     旋转矢量 σ
 * ```
 * σ ≤ 0 表示"该轴完全不抖"（原生层按位直接跳过）。
 */
object SensorNoise {

    const val COUNT = 20

    const val GYRO = 0
    const val GYRO_BIAS = 3
    const val ACCEL = 6
    const val GRAVITY = 9
    const val LINEAR = 12
    const val MAG = 15
    const val ORIENT = 18
    const val ROTVEC = 19

    /** 零偏槽（可为负）——Gradle/Java 侧判定用 */
    fun isBiasSlot(index: Int): Boolean = index in GYRO_BIAS..(GYRO_BIAS + 2)

    /**
     * 默认 σ = 旧硬编码半宽 ÷ √3（旧口径是均匀分布 [−A,A]，σ = A/√3）
     * ⇒ 不改动时**方差与旧行为一致**，只是分布由均匀改为高斯。陀螺零偏默认 0（旧实现没有该项）。
     */
    val DEFAULTS = floatArrayOf(
        0.000577f, 0.000577f, 0.000577f,   // 陀螺 σ（0.001/√3）
        0.0f, 0.0f, 0.0f,                  // 陀螺零偏
        0.005774f, 0.005774f, 0.005774f,   // 加速度计 σ（0.01/√3）
        0.005774f, 0.005774f, 0.005774f,   // 重力 σ
        0.005774f, 0.005774f, 0.005774f,   // 线性加速度 σ
        0.2078f, 0.1212f, 0.3233f,         // 磁场 σ（0.36/0.21/0.56 ÷ √3）
        0.0866f,                           // 方向角 σ（0.15/√3）
        0.000866f,                         // 旋转矢量 σ（0.0015/√3）
    )

    /** 分量语义：三轴（x/y/z）或标量（方向角/旋转矢量） */
    enum class Shape { XYZ, SCALAR }

    /**
     * 一行可编辑项：覆盖 profile 的连续片段。
     * @param start  profile 起始下标
     * @param max    单项上限（负值下限 = −max，仅零偏行有）
     * @param digits 显示/建议小数位
     */
    data class Item(
        val title: String,
        val unit: String,
        val desc: String,
        val start: Int,
        val shape: Shape,
        val max: Float,
        val digits: Int,
    ) {
        val size: Int get() = if (shape == Shape.XYZ) 3 else 1
        val end: Int get() = start + size - 1
        val isBias: Boolean get() = start == GYRO_BIAS
    }

    val ITEMS: List<Item> = listOf(
        Item(
            "陀螺仪噪声 σ", "rad/s",
            "逐轴标准差（x/y/z）。PKG110 真机静止实测 σ≈0.001（旧实现误取 0.005，大 5 倍）",
            GYRO, Shape.XYZ, 0.05f, 4,
        ),
        Item(
            "陀螺仪零偏 μ", "rad/s",
            "逐轴零偏（可为负）。陀螺零参考物理上就是 0 ⇒ 实测中位数即真实零偏；" +
                "PKG110 实测 z≈0.09，一键校准会填这一行",
            GYRO_BIAS, Shape.XYZ, 0.5f, 4,
        ),
        Item(
            "加速度计噪声 σ", "m/s²",
            "逐轴标准差。PKG110 真机静止 σ≈0.007/0.007/0.009",
            ACCEL, Shape.XYZ, 0.5f, 4,
        ),
        Item(
            "重力噪声 σ", "m/s²",
            "逐轴标准差。重力是低通后的结果，真机比加速度计安静得多（≈0.001）",
            GRAVITY, Shape.XYZ, 0.5f, 4,
        ),
        Item(
            "线性加速度噪声 σ", "m/s²",
            "逐轴标准差（静止时它就是「加速度计减去重力」的残差）",
            LINEAR, Shape.XYZ, 0.5f, 4,
        ),
        Item(
            "磁场噪声 σ", "µT",
            "逐轴标准差。PKG110 真机静止 σ≈0.21/0.12/0.32；磁场受环境影响大，建议在常用环境下校准",
            MAG, Shape.XYZ, 5f, 3,
        ),
        Item(
            "方向角噪声 σ", "°",
            "虚拟方位角的抖动。方向角是融合量（真机由磁+陀螺合成），**不参与一键校准**",
            ORIENT, Shape.SCALAR, 5f, 3,
        ),
        Item(
            "旋转矢量噪声 σ", "—",
            "旋转矢量四元的抖动（融合量，**不参与一键校准**）",
            ROTVEC, Shape.SCALAR, 0.05f, 4,
        ),
    )

    /** 每个槽的显示小数位（取所属行的小数位） */
    private val DIGITS = IntArray(COUNT) { i ->
        ITEMS.firstOrNull { i in it.start..it.end }?.digits ?: 4
    }

    /** 单值显示（按该槽所属行的小数位） */
    fun format(index: Int, value: Float): String =
        String.format("%.${DIGITS[index.coerceIn(0, COUNT - 1)]}f", value)

    /** 一行的显示串：三轴用 "/" 连接 */
    fun formatItem(item: Item, values: FloatArray): String =
        (item.start..item.end).joinToString("/") { format(it, values.getOrElse(it) { 0f }) }

    /**
     * 解析用户输入：[item] 行的新值。
     * 接受 "/" 或 "," 分隔的多值，以及两轴/三轴；**只给一个数时填满整行**（三轴同值）。
     * @return null = 输入非法（附 [error] 说明）
     */
    fun parseItem(item: Item, text: String): Result<FloatArray> {
        val parts = text.trim().split('/', ',', '，', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Result.failure(IllegalArgumentException("请输入数值"))
        val nums = parts.map { it.toFloatOrNull() ?: return Result.failure(IllegalArgumentException("「$it」不是数值")) }
        val filled = when {
            nums.size == 1 -> FloatArray(item.size) { nums[0] }
            nums.size == item.size -> nums.toFloatArray()
            else -> return Result.failure(IllegalArgumentException("需要 ${item.size} 个数值（用 / 分隔）"))
        }
        for (v in filled) {
            if (v.isNaN()) return Result.failure(IllegalArgumentException("不是数值"))
            if (!item.isBias && v < 0f) return Result.failure(IllegalArgumentException("σ 不能为负"))
            if (kotlin.math.abs(v) > item.max) {
                return Result.failure(IllegalArgumentException("单项上限 %.${item.digits}f".format(item.max)))
            }
        }
        return Result.success(filled)
    }

    /** 把 [item] 行的新值写进 [values]（返回新数组，不改原数组） */
    fun withItem(values: FloatArray, item: Item, filled: FloatArray): FloatArray {
        val out = sanitize(values)
        for (i in 0 until item.size) out[item.start + i] = filled[i]
        return out
    }

    /**
     * 规范化外部数据（pref / Bundle）：长度补齐或截断到 [COUNT]，NaN 与非法符号退回默认，
     * 超上限钳位。返回的数组总是可用的。
     */
    fun sanitize(values: FloatArray?): FloatArray {
        val out = DEFAULTS.copyOf()
        if (values == null) return out
        for (i in 0 until COUNT) {
            val v = values.getOrNull(i) ?: continue
            if (v.isNaN()) continue
            val max = ITEMS.firstOrNull { i in it.start..it.end }?.max ?: 50f
            out[i] = when {
                isBiasSlot(i) -> v.coerceIn(-max, max)
                v < 0f -> 0f
                else -> v.coerceAtMost(max)
            }
        }
        return out
    }

    /** "a,b,c,…" 形式的持久化串（pref 用） */
    fun encode(values: FloatArray): String = values.joinToString(",")

    /** 解析 [encode] 的产物；损坏/空串 ⇒ 全默认 */
    fun decode(text: String?): FloatArray {
        if (text.isNullOrBlank()) return DEFAULTS.copyOf()
        val parsed = text.split(',').mapNotNull { it.trim().toFloatOrNull() }
        return sanitize(parsed.toFloatArray())
    }
}
