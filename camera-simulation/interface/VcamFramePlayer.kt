package moe.fuqiuluo.portalex.vcam

/**
 * Pure playback state machine for the camera simulation standard interface.
 * It has no Android dependencies; the device backend only needs the shared
 * frame channel, while the app drives this state machine.
 */
class VcamFramePlayer {

    data class Frame(val width: Int, val height: Int, val nv21: ByteArray)

    data class LoadResult(val accepted: Int, val total: Int)

    class VcamException(message: String) : Exception(message)

    private val frames = ArrayList<Frame>()

    var currentIndex: Int = -1
        private set

    var paused: Boolean = false
        private set

    var ended: Boolean = false
        private set

    var loop: Boolean = false
        private set

    var stay: Boolean = true
        private set

    /** 0 = unlimited */
    var limitFps: Int = 0
        private set

    private var lastOutputIndex = -1
    private var lastOutputTimeNs = 0L

    val count: Int get() = frames.size
    val isEmpty: Boolean get() = frames.isEmpty()

    @Synchronized
    fun load(input: List<Frame>, clear: Boolean = false): LoadResult {
        if (input.isNotEmpty()) {
            val first = input.first()
            require(first.width > 0 && first.height > 0) { "invalid frame size" }
            require(first.nv21.size >= first.width * first.height * 3 / 2) {
                "invalid NV21 buffer"
            }
            input.forEach {
                require(it.width == first.width && it.height == first.height) {
                    "all frames must have the same size"
                }
                require(it.nv21.size >= it.width * it.height * 3 / 2) {
                    "invalid NV21 buffer"
                }
            }
        }
        if (clear) {
            frames.clear()
            currentIndex = -1
            paused = false
            ended = false
            lastOutputIndex = -1
        }
        if (input.isNotEmpty()) {
            frames.addAll(input)
            if (currentIndex < 0) currentIndex = 0
            ended = false
        }
        return LoadResult(input.size, frames.size)
    }

    @Synchronized
    fun clear() {
        frames.clear()
        currentIndex = -1
        paused = false
        ended = false
        lastOutputIndex = -1
        lastOutputTimeNs = 0
    }

    @Synchronized
    fun pause(index: Int): Int {
        if (frames.isEmpty()) throw VcamException("NO_FRAMES")
        if (index < 0) throw VcamException("INVALID_INDEX")
        if (index == 0) {
            if (currentIndex < 0) currentIndex = 0
        } else {
            if (index > frames.size) throw VcamException("INVALID_INDEX")
            currentIndex = index - 1
        }
        paused = true
        ended = false
        return currentIndex
    }

    @Synchronized
    fun resume(index: Int): Int {
        if (frames.isEmpty()) throw VcamException("NO_FRAMES")
        if (index < 0) throw VcamException("INVALID_INDEX")
        if (index > 0) {
            if (index > frames.size) throw VcamException("INVALID_INDEX")
            currentIndex = index - 1
        } else if (currentIndex < 0) {
            currentIndex = 0
        }
        paused = false
        ended = false
        return currentIndex
    }

    @Synchronized
    fun limit(page: Int) {
        if (page < 0) throw VcamException("INVALID_FPS")
        limitFps = page
    }

    @Synchronized
    fun loop(state: Boolean) {
        loop = state
        if (state) ended = false
    }

    @Synchronized
    fun stay(state: Boolean) {
        stay = state
    }

    /** Returns null when the caller must output black. */
    @Synchronized
    fun nextFrame(nowNs: Long = System.nanoTime()): Frame? {
        if (frames.isEmpty()) return null
        if (paused) {
            val i = currentIndex.coerceIn(0, frames.lastIndex)
            return frames[i]
        }
        if (limitFps > 0 && lastOutputIndex >= 0 && lastOutputTimeNs != 0L) {
            val interval = 1_000_000_000L / limitFps
            if (nowNs - lastOutputTimeNs < interval) {
                return frames[lastOutputIndex]
            }
        }
        if (ended && !loop) {
            return if (stay) frames.last() else null
        }
        if (ended && loop) {
            currentIndex = 0
            ended = false
        } else if (currentIndex < 0) {
            currentIndex = 0
        } else {
            currentIndex++
        }
        if (currentIndex >= frames.size) {
            if (loop) {
                currentIndex = 0
            } else {
                currentIndex = frames.lastIndex
                ended = true
                return if (stay) frames.last() else null
            }
        }
        lastOutputIndex = currentIndex
        lastOutputTimeNs = nowNs
        return frames[currentIndex]
    }

    @Synchronized
    fun status(): String = "frames=${frames.size} index=$currentIndex paused=$paused " +
            "ended=$ended loop=$loop stay=$stay limit=$limitFps"
}
