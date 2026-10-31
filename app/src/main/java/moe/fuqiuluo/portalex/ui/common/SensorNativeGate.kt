package moe.fuqiuluo.portalex.ui.common

import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.ext.suPath
import moe.fuqiuluo.portalex.service.SensorNativeLibrary

/**
 * 传感器模拟开关的前置门禁：检查固定 native 库。
 *
 * - 已存在且与 APK 一致：直接放行；
 * - 缺失/不一致：先告知用户将用 su 释放库并重启 system_server；
 * - 用户取消 / su 失败 / 重启失败：明确提示，并让调用方回退开关。
 */
object SensorNativeGate {

    fun ensure(
        fragment: Fragment,
        onReady: () -> Unit,
        onBlocked: () -> Unit,
    ) {
        val ctx = fragment.requireContext()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { SensorNativeLibrary.check(ctx) }
            if (status.isSuccess && status.getOrThrow().same) {
                onReady()
                return@launch
            }
            if (!fragment.isAdded || fragment.view == null) return@launch
            val reason = status.exceptionOrNull()?.let { SensorNativeLibrary.errorText(it) }
            val message = buildString {
                append(fragment.getString(R.string.sensor_native_message, ctx.suPath))
                if (reason != null) append("\n\n").append(fragment.getString(R.string.sensor_native_check_failed, reason))
            }
            showInstallPrompt(fragment, message) { restart ->
                install(fragment, restart, onBlocked)
            }
        }
    }

    /**
     * “立即更新”：不做 same 检查，强制用当前 APK 的库替换固定文件。
     * 复用与缺失库完全相同的安装提示、复选框和按钮，避免出现两套确认语义。
     */
    fun update(fragment: Fragment, onChanged: () -> Unit) {
        val ctx = fragment.requireContext()
        val message = fragment.getString(R.string.sensor_native_message, ctx.suPath)
        showInstallPrompt(fragment, message) { restart ->
            install(fragment, restart, onChanged, R.string.sensor_native_updated)
        }
    }

    /** “移除自定义库”：删除固定文件；当前进程仍用旧映射，重启后才彻底不加载。 */
    fun remove(fragment: Fragment, onRemoved: () -> Unit) {
        val ctx = fragment.requireContext()
        val choice = restartChoice(fragment)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.sensor_native_title)
            .setMessage(R.string.sensor_native_remove_message)
            .setView(choice.view)
            .setCancelable(false)
            .setPositiveButton(R.string.sensor_native_remove) { _, _ ->
                removeNow(fragment, choice.checkBox.isChecked, onRemoved)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 统一的安装/更新确认弹窗。message 由调用方传入，文案可在资源里调整；
     * 结构（标题、复选框、按钮、取消语义）只有这一处。
     */
    private fun showInstallPrompt(
        fragment: Fragment,
        message: String,
        onConfirm: (restart: Boolean) -> Unit,
    ) {
        val ctx = fragment.requireContext()
        val choice = restartChoice(fragment)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.sensor_native_title)
            .setMessage(message)
            .setView(choice.view)
            .setCancelable(false)
            .setPositiveButton(R.string.sensor_native_install) { _, _ ->
                onConfirm(choice.checkBox.isChecked)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class RestartChoice(val view: android.view.View, val checkBox: CheckBox)

    /** 与其它 dialog 对齐：使用统一的 24dp 内容内边距，默认不自动重启。 */
    private fun restartChoice(fragment: Fragment): RestartChoice {
        val view = LayoutInflater.from(fragment.requireContext())
            .inflate(R.layout.dialog_sensor_native_restart, null)
        val box = view.findViewById<CheckBox>(R.id.restart_system_server)
        box.isChecked = false
        return RestartChoice(view, box)
    }

    private fun removeNow(fragment: Fragment, restart: Boolean, onRemoved: () -> Unit) {
        val ctx = fragment.requireContext()
        val progress = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.sensor_native_title)
            .setMessage(R.string.sensor_native_removing)
            .setCancelable(false)
            .show()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { SensorNativeLibrary.remove(ctx) }
            progress.dismiss()
            result.onFailure { t ->
                showFailure(fragment, fragment.getString(
                    R.string.sensor_native_remove_failed,
                    SensorNativeLibrary.errorText(t),
                ))
                return@launch
            }
            onRemoved()
            if (!restart) {
                showFailure(fragment, fragment.getString(R.string.sensor_native_removed))
                return@launch
            }
            Toast.makeText(ctx, R.string.sensor_native_restarting, Toast.LENGTH_LONG).show()
            val restartResult = withContext(Dispatchers.IO) { SensorNativeLibrary.restartSystemServer(ctx) }
            restartResult.onFailure { t ->
                showFailure(fragment, fragment.getString(
                    R.string.sensor_native_restart_failed,
                    SensorNativeLibrary.errorText(t),
                ))
            }
        }
    }

    private fun install(
        fragment: Fragment,
        restart: Boolean,
        onBlocked: () -> Unit,
        successNoRestartRes: Int = R.string.sensor_native_restart_skipped,
    ) {
        val ctx = fragment.requireContext()
        val progress = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.sensor_native_title)
            .setMessage(R.string.sensor_native_installing)
            .setCancelable(false)
            .show()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { SensorNativeLibrary.install(ctx) }
            progress.dismiss()
            result.onFailure { t ->
                showFailure(fragment, fragment.getString(
                    R.string.sensor_native_install_failed,
                    SensorNativeLibrary.errorText(t),
                ))
                onBlocked()
                return@launch
            }
            if (!restart) {
                showFailure(fragment, fragment.getString(successNoRestartRes))
                onBlocked()
                return@launch
            }
            Toast.makeText(ctx, R.string.sensor_native_restarting, Toast.LENGTH_LONG).show()
            val restartResult = withContext(Dispatchers.IO) { SensorNativeLibrary.restartSystemServer(ctx) }
            restartResult.onFailure { t ->
                showFailure(fragment, fragment.getString(
                    R.string.sensor_native_restart_failed,
                    SensorNativeLibrary.errorText(t),
                ))
                onBlocked()
            }
            // 成功重启时 app 通常也会被杀掉；这里不再调用 onReady，避免假成功。
        }
    }

    private fun showFailure(fragment: Fragment, message: String) {
        if (!fragment.isAdded) return
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.sensor_native_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
