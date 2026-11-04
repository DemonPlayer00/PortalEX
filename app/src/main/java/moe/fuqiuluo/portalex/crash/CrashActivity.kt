package moe.fuqiuluo.portalex.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.util.TypedValue
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 独立 :crash 进程中的错误展示页；主进程已崩溃也能显示。 */
class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val details = intent.getStringExtra(CrashHandler.EXTRA_ERROR)
            ?: "没有可显示的错误信息"
        val text = TextView(this).apply {
            this.text = details
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            gravity = Gravity.START or Gravity.TOP
        }
        val scroll = ScrollView(this).apply {
            addView(text)
            isFillViewport = true
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(CrashHandler.TITLE)
            .setView(scroll)
            .setCancelable(false)
            .setPositiveButton("退出", null)
            .setNegativeButton("复制错误", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                finishAndRemoveTask()
                Process.killProcess(Process.myPid())
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(CrashHandler.TITLE, details))
                Toast.makeText(this, "错误信息已复制", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
