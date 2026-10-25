package moe.fuqiuluo.portalex.ui.test

/** Test 页面最后一次渲染的文本，供 root 调试命令读取。 */
object TestSnapshot {
    @Volatile var last: String? = null
}
