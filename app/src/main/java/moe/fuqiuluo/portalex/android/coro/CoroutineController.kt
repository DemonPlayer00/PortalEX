package moe.fuqiuluo.portalex.android.coro

import kotlinx.coroutines.channels.Channel

class CoroutineController {
    private val controlChannel = Channel<ControlCommand>(Channel.UNLIMITED)

    /**
     * **初值必须为 true（默认「暂停 = 不推进」）**。
     *
     * 本控制器的语义是「摇杆被按住才推进」：按下摇杆 [resume]、松开 [pause]。
     * 若初值为 false，运动推进器在「谁也没碰摇杆」时每 tick 都会位移一次——
     * 表现为**一启动模拟，位置就朝 [FakeLoc.bearing] 的固定方向匀速漂移**，
     * 直到用户操作一次摇杆（触发 [pause]）才停下。上游 `initRocker` 原本在启动
     * 循环前显式调用 `pause()`，重构遗失后该语义只能由初值兜住。
     */
    var isPaused = true

    /**
     * 挂起式暂停门（旧用法，保留给「可以整条挂起」的独立循环）。
     * 运动推进器请用 [consume]——与其它分支共用循环时挂起会互相卡住。
     */
    suspend fun controlledCoroutine() {
        checkControl()
    }

    private suspend fun checkControl() {
        controlChannel.tryReceive().getOrNull()?.let {
            when (it) {
                ControlCommand.Pause -> {
                    isPaused = true
                    while (controlChannel.receive() != ControlCommand.Resume) {
                        // do nothing
                    }
                    isPaused = false
                }
                ControlCommand.Resume -> {}
            }
        }
    }

    /**
     * 非阻塞地消费全部待处理指令，返回「当前是否处于暂停」。
     *
     * 与 [controlledCoroutine] 的区别：**不让调用方挂起**——合并后的运动推进器里，
     * 摇杆暂停门与路线播放共用同一条循环，若在暂停时挂起等待 Resume，则「松开摇杆 →
     * 启动自动播放」会永久卡死（没人再发 Resume）→ 播放停在原地。
     * 暂停只是「本 tick 不动」，循环必须继续转。
     */
    fun consume(): Boolean {
        while (true) {
            val command = controlChannel.tryReceive().getOrNull() ?: break
            when (command) {
                ControlCommand.Pause -> isPaused = true
                ControlCommand.Resume -> isPaused = false
            }
        }
        return isPaused
    }

    fun pause() {
        controlChannel.trySend(ControlCommand.Pause)
    }

    fun resume() {
        controlChannel.trySend(ControlCommand.Resume)
    }
}

enum class ControlCommand {
    Pause,
    Resume
}