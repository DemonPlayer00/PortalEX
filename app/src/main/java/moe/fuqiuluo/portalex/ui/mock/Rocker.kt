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
            autoStatus = !autoStatus
            playAuto(rockerView)
        }
        root.findViewById<View>(R.id.auto_status).setOnClickListener {

        }
        root.findViewById<View>(R.id.auto_lock).setOnClickListener {
            autoLockStatus = !autoLockStatus
            if (autoLockStatus) {
                root.findViewById<View>(R.id.auto_lock).setBackgroundResource(R.drawable.baseline_lock_24)
            } else {
                root.findViewById<View>(R.id.auto_lock).setBackgroundResource(R.drawable.baseline_manual_24)
            }
        }
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
