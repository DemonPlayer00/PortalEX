package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.fuqiuluo.portalex.R

/**
 * 摄像头素材导入/导出期间的阻塞加载框。
 *
 * 只能由调用方在任务结束时关闭：外部点击与返回键都禁用，避免用户在写文件/上传
 * 进行到一半时把界面关掉。
 */
class CameraLoadingDialog(private val context: Context) {

    private var dialog: AlertDialog? = null
    private var textView: TextView? = null

    fun show(message: CharSequence) {
        val current = dialog
        if (current?.isShowing == true) {
            textView?.text = message
            return
        }
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_camera_loading, null, false)
        textView = view.findViewById(R.id.camera_loading_text)
        textView?.text = message
        dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setCancelable(false)
            .create()
            .also {
                it.setCanceledOnTouchOutside(false)
                it.show()
            }
    }

    fun dismiss() {
        runCatching { dialog?.takeIf { it.isShowing }?.dismiss() }
        dialog = null
        textView = null
    }
}
