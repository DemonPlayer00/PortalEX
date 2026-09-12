package moe.fuqiuluo.portalex.ui.camera

/**
 * 摄像头模拟的**进程内运行时状态**（app 进程）：当前表象位置 + 模块侧回传的拦截记录。
 *
 * 为什么位置要经这里转一手：provider 的 `call()` 跑在 binder 线程上，而"当前模拟位置"
 * 只有持有定位服务连接的地方（MainActivity 的 ViewModel）才拿得到 —— 由它注册一个
 * 取值函数进来，provider 只负责调用。拿不到就如实说"未知"（模块侧据此不做距离比较），
 * **绝不猜一个坐标**：猜错会让"最近的视频点"变成随机点。
 */
object CameraMockRuntime {

    /** 由 MainActivity（定位服务握手成功后）注册；返回 wgs84 坐标，null = 当前不可知 */
    @Volatile
    var positionSupplier: (() -> Pair<Double, Double>?)? = null

    private const val MAX_LOG = 20
    private val accesses = ArrayDeque<String>()
    private val reports = ArrayDeque<String>()

    /** 当前表象位置（wgs84）。任何异常都当"不可知"处理 —— provider 不该因为取位置而失败 */
    fun position(): Pair<Double, Double>? =
        runCatching { positionSupplier?.invoke() }.getOrNull()

    @Synchronized
    fun noteAccess(text: String) {
        accesses.addLast(text)
        while (accesses.size > MAX_LOG) accesses.removeFirst()
    }

    @Synchronized
    fun noteReport(text: String) {
        reports.addLast(text)
        while (reports.size > MAX_LOG) reports.removeFirst()
    }

    /** Test 页观测用：最近一次 provider 访问（谁、何时、要什么） */
    @Synchronized
    fun dumpAccess(): String = if (accesses.isEmpty()) "（无）" else accesses.joinToString("\n")

    /** Test 页观测用：模块侧回传的拦截结果（选中了哪个视频、从第几毫秒起播） */
    @Synchronized
    fun dumpReports(): String = if (reports.isEmpty()) "（无）" else reports.joinToString("\n")

    @Synchronized
    fun clear() {
        accesses.clear()
        reports.clear()
    }
}
