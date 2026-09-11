package moe.fuqiuluo.xposed.utils

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * 静默失败记账器。
 *
 * 它存在的理由：本项目最容易出、最难查的故障是"错了但不报错"（注入层没装上、速率提示
 * 没灌进去…）。这些失败现在必须**计数可见** —— 所以计数本身要先被测住：
 * 计数准确、只列非零、可归零、并发不丢。
 */
class PortalDiagTest {

    // 记账器是**全局单例**，而别的测试类也会触发记账（例如 InstallOffsetsTest 的拒绝路径
    // 会记 NATIVE_INSTALL）⇒ 前后都要归零，否则用例顺序会互相污染（这个坑当场踩到过）。
    @Before
    fun setUp() = PortalDiag.reset()

    @After
    fun tearDown() = PortalDiag.reset()

    @Test
    fun countsPerArea() {
        PortalDiag.fail(PortalDiag.Area.RATE_HINTS)
        PortalDiag.fail(PortalDiag.Area.RATE_HINTS)
        PortalDiag.fail(PortalDiag.Area.RT_SEND)
        assertEquals(2L, PortalDiag.count(PortalDiag.Area.RATE_HINTS))
        assertEquals(1L, PortalDiag.count(PortalDiag.Area.RT_SEND))
        assertEquals(0L, PortalDiag.count(PortalDiag.Area.LIB_RESOLVE))
    }

    @Test
    fun dump_listsOnlyNonZero_andSaysNoneWhenClean() {
        assertEquals("none", PortalDiag.dump())
        PortalDiag.fail(PortalDiag.Area.NATIVE_LOAD)
        val text = PortalDiag.dump()
        assertTrue("应含区域名与次数：$text", text.contains("NATIVE_LOAD=1"))
        assertTrue("不该列未发生的区域：$text", !text.contains("LIB_RESOLVE"))
        assertTrue("不该含 none：$text", !text.contains("none"))
    }

    @Test
    fun reset_clearsEverything() {
        PortalDiag.fail(PortalDiag.Area.HANDLE_MAP)
        PortalDiag.reset()
        assertEquals("none", PortalDiag.dump())
        assertEquals(0L, PortalDiag.count(PortalDiag.Area.HANDLE_MAP))
    }

    @Test
    fun failureWithThrowable_stillCounts() {
        PortalDiag.fail(PortalDiag.Area.NATIVE_INSTALL, IllegalStateException("boom"))
        assertEquals(1L, PortalDiag.count(PortalDiag.Area.NATIVE_INSTALL))
    }

    @Test
    fun concurrentFailure_doesNotLoseCounts() {
        // 记账发生在 supervisor / pump / 命令通道等多个线程上，丢计数等于"又变回静默"
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) {
            pool.submit {
                start.await()
                repeat(perThread) { PortalDiag.fail(PortalDiag.Area.RT_SEND) }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals((threads * perThread).toLong(), PortalDiag.count(PortalDiag.Area.RT_SEND))
    }
}
