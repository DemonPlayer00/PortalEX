package moe.fuqiuluo.portalex.ui.mock

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.cardview.widget.CardView
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.android.widget.RockerView
import moe.fuqiuluo.portalex.ext.rockerCoords


@SuppressLint("RtlHardcoded", "ClickableViewAccessibility")
class Rocker(private val activity: Activity) : View.OnTouchListener {
    /** 宿主 Activity：供 ViewModel 检测 Activity 重建后是否已过期（旧实例不能再 addView） */
    val hostActivity: Activity get() = activity

    private val root by lazy {
        LayoutInflater.from(activity).inflate(R.layout.layout_rocker, null)!!
    }
    private val layoutParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            0, 0, PixelFormat.TRANSPARENT
        )
    }
    private val windowManager by lazy { activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var startX = 0
    private var startY = 0

    var isStart = false
    var isHide = false
    private var autoCardVisible = false
    var onAutoPlayRequest: (() -> Boolean)? = null

    var autoStatus = false
        get() = field
        set(value) {
            field = value
            playAuto(root.findViewById(R.id.rocker))
        }
    var autoLockStatus = false

    init {
        layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            layoutParams.type = WindowManager.LayoutParams.TYPE_PHONE
        }

        val rockerCoords = activity.rockerCoords
        layoutParams.format = PixelFormat.RGBA_8888
        layoutParams.gravity = Gravity.LEFT or Gravity.TOP
        layoutParams.x = rockerCoords.first
        layoutParams.y = rockerCoords.second

        root.setOnTouchListener(this)
        
        
        root.findViewById<View>(R.id.expand_menu).setOnClickListener {
            Toast.makeText(activity, "暂不支持", Toast.LENGTH_SHORT).show()
        }
        val autoCard = root.findViewById<CardView>(R.id.auto_card)
        autoCard.visibility = if (autoCardVisible) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.auto).setOnClickListener {
            autoCardVisible = !autoCardVisible
            autoCard.visibility = if (autoCardVisible) View.VISIBLE else View.GONE
        }
        val rockerView = root.findViewById<RockerView>(R.id.rocker)
        val autoView = root.findViewById<AppCompatImageView>(R.id.auto)
        val expandMenuView = root.findViewById<AppCompatImageView>(R.id.expand_menu)
        
        root.findViewById<View>(R.id.move).setOnClickListener {
            isHide = !isHide
            switchHide(rockerView, autoView, expandMenuView)
        }
        
        root.findViewById<View>(R.id.auto_play).setOnClickListener {
            if (!autoStatus && onAutoPlayRequest?.invoke() == false) {
                Toast.makeText(activity, R.string.route_too_short, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            autoStatus = !autoStatus
            playAuto(rockerView)
        }
        root.findViewById<View>(R.id.auto_status).setOnClickListener {

        }
        root.findViewById<View>(R.id.auto_lock).setOnClickListener {
            autoLockStatus = !autoLockStatus
            applyKeepScreenOn(autoLockStatus)
            renderAutoLock()
        }
    }

    /**
     * 屏幕常亮开关：在悬浮窗自身窗口上加 FLAG_KEEP_SCREEN_ON。
     *
     * 不修改系统 Settings 的超时值，只在窗口可见期间阻止息屏；关闭开关或隐藏悬浮窗即恢复。
     */
    private fun applyKeepScreenOn(on: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        layoutParams.flags = if (on) layoutParams.flags or flag else layoutParams.flags and flag.inv()
        if (isStart) {
            runCatching { windowManager.updateViewLayout(root, layoutParams) }
                .onFailure { Log.w("Rocker", "更新屏幕常亮标志失败", it) }
        }
    }

    private fun renderAutoLock() {
        val view = root.findViewById<AppCompatImageView>(R.id.auto_lock)
        view.setImageResource(
            if (autoLockStatus) R.drawable.baseline_lightbulb_24
            else R.drawable.baseline_lightbulb_outline_24
        )
        view.imageTintList = android.content.res.ColorStateList.valueOf(
            if (autoLockStatus) android.graphics.Color.rgb(255, 193, 7)
            else androidx.core.content.ContextCompat.getColor(activity, R.color.rocker_icon_tint)
        )
    }

    private fun playAuto(rockerView: RockerView) {
        if (autoStatus) {
            root.findViewById<View>(R.id.auto_play)
                .setBackgroundResource(R.drawable.baseline_stop_24)
        } else {
            root.findViewById<View>(R.id.auto_play)
                .setBackgroundResource(R.drawable.baseline_play_24)
            upController()
        }
        rockerView.auto(autoStatus)
    }
    
    private fun switchHide(rockerView: RockerView, autoView: AppCompatImageView, expandMenuView: AppCompatImageView) {
        if (isHide) {
            if(!autoCardVisible)root.findViewById<View>(R.id.auto).callOnClick()
            autoView.setVisibility(View.GONE)
            rockerView.setVisibility(View.GONE)
            expandMenuView.setVisibility(View.GONE)
        } else {
            autoView.setVisibility(View.VISIBLE)
            rockerView.setVisibility(View.VISIBLE)
            expandMenuView.setVisibility(View.VISIBLE)
        }
    }
    
    /**
     * 显示悬浮摇杆；返回是否真的加上窗口。
     * addView 在悬浮窗权限被回收、窗口已存在、宿主 Activity 已销毁等情况下会抛
     * BadTokenException / IllegalStateException——兜底处理，不让悬浮窗失败变成闪退。
     */
    fun show(): Boolean {
        if (isStart) return true
        return try {
            windowManager.addView(root, layoutParams)
            isStart = true
            true
        } catch (e: Exception) {
            Log.e("Rocker", "显示悬浮摇杆失败", e)
            false
        }
    }

    /** 隐藏悬浮摇杆；返回是否成功（本来就没显示时视为成功）。 */
    fun hide(): Boolean {
        if (!isStart) return true
        return try {
            // 悬浮窗消失即不再需要屏幕常亮，避免开关状态与系统行为脱节
            if (autoLockStatus) {
                autoLockStatus = false
                applyKeepScreenOn(false)
                renderAutoLock()
            }
            val rockerView = root.findViewById<RockerView>(R.id.rocker)
            rockerView.reset()
            windowManager.removeView(root)
            isStart = false
            true
        } catch (e: Exception) {
            Log.e("Rocker", "移除悬浮摇杆失败", e)
            false
        }
    }

    fun savePosition() {
        activity.rockerCoords = Pair(layoutParams.x, layoutParams.y)
    }

    fun updateView() {
        windowManager.updateViewLayout(root, layoutParams)
    }

    fun setRockerListener(listener: RockerView.Companion.OnMoveListener) {
        val rockerView = root.findViewById<RockerView>(R.id.rocker)
        rockerView.listener = listener
    }

    fun invokeOnTouchEvent(joystickX: Float, joystickY: Float) {
        val rockerView = root.findViewById<RockerView>(R.id.rocker)
        // 模拟摇杆移动 调用onTouchEvent
        rockerView.onTouchEvent(MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_DOWN, joystickX, joystickY, 0))
        rockerView.onTouchEvent(MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_MOVE, joystickX, joystickY, 0))
        rockerView.auto(true)
    }

    fun upController() {
        val rockerView = root.findViewById<RockerView>(R.id.rocker)
        // 模拟摇杆移动 调用onTouchEvent
        rockerView.onTouchEvent(MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_UP, 0f, 0f, 0))
        rockerView.auto(false)
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        if (v == null || event == null) {
            return false
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX.toInt()
                startY = event.rawY.toInt()

                savePosition()
            }

            MotionEvent.ACTION_MOVE -> {
                val nowX = event.rawX.toInt()
                val nowY = event.rawY.toInt()
                val movedX = nowX - startX
                val movedY = nowY - startY
                startX = nowX
                startY = nowY
                layoutParams.x += movedX
                layoutParams.y += movedY
                windowManager.updateViewLayout(v, layoutParams)
            }

            else -> {}
        }
        return false
    }
}
