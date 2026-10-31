package moe.fuqiuluo.portalex.ui.common

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
            val restartBox = android.widget.CheckBox(ctx).apply {
                setText(R.string.sensor_native_restart_checkbox)
                isChecked = true
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.sensor_native_title)
                .setMessage(message)
                .setView(restartBox)
                .setCancelable(false)
                .setPositiveButton(R.string.sensor_native_install) { _, _ ->
                    install(fragment, restartBox.isChecked, onBlocked)
                }
                .setNegativeButton(R.string.cancel) { _, _ -> onBlocked() }
                .show()
        }
    }

    private fun install(fragment: Fragment, restart: Boolean, onBlocked: () -> Unit) {
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
                showFailure(fragment, fragment.getString(R.string.sensor_native_restart_skipped))
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
