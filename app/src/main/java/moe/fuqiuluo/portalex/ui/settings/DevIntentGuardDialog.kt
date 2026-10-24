package moe.fuqiuluo.portalex.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import moe.fuqiuluo.portalex.ext.devIntentGuardEnabled
import moe.fuqiuluo.portalex.ext.devIntentGuardPackages
import moe.fuqiuluo.portalex.service.MockServiceHelper
import java.util.concurrent.Executors

/** 开发者 Intent 拦截设置：弹窗内搜索、总开关、应用列表。 */
object DevIntentGuardDialog {
    private data class AppEntry(val pkg: String, val label: String)

    fun show(context: Context) {
        val selected = LinkedHashSet(context.devIntentGuardPackages)
        val all = ArrayList<AppEntry>()
        val executor = Executors.newSingleThreadExecutor()
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val switch = SwitchMaterial(context).apply {
            text = "启用拦截"
            isChecked = context.devIntentGuardEnabled
            setPadding(0, dp(6), 0, dp(6))
        }
        val summary = TextView(context).apply {
            textSize = 12f
        }
        val search = EditText(context).apply {
            hint = "搜索应用名或包名"
            setSingleLine(true)
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            addView(list)
        }
        root.addView(switch)
        root.addView(summary)
        root.addView(search)
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(360)))

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("阻止开发者 Intent")
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()

        fun push() {
            context.devIntentGuardEnabled = switch.isChecked
            context.devIntentGuardPackages = selected
            val lm = context.getSystemService(LocationManager::class.java) ?: return
            val enabled = switch.isChecked
            val packages = selected.toSet()
            executor.execute { MockServiceHelper.setDevIntentGuard(lm, enabled, packages) }
        }

        fun render() {
            val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
            list.removeAllViews()
            val on = switch.isChecked
            // 已勾选的应用始终置顶；同一组内仍按名称排序，搜索只缩小候选集。
            val ordered = all.sortedWith(
                compareByDescending<AppEntry> { it.pkg in selected }
                    .thenBy { it.label.lowercase() }
            )
            for (app in ordered) {
                if (q.isNotEmpty() &&
                    !app.label.lowercase().contains(q) &&
                    !app.pkg.lowercase().contains(q)
                ) continue
                list.addView(CheckBox(context).apply {
                    text = "${app.label}  (${app.pkg})"
                    isChecked = app.pkg in selected
                    isEnabled = on
                    alpha = if (on) 1f else 0.4f
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected += app.pkg else selected -= app.pkg
                        context.devIntentGuardPackages = selected
                        push()
                        // 勾选/取消后重新排序，让选中的应用置顶。
                        render()
                    }
                })
            }
            summary.text = "已选择 ${selected.size} 个应用" +
                if (on) "" else "（拦截已关闭，列表只读）"
        }

        switch.setOnCheckedChangeListener { _, _ ->
            render()
            push()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { render() }
            override fun afterTextChanged(s: Editable?) {}
        })

        summary.text = "正在读取应用列表…"
        executor.execute {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
                .toList()
            android.os.Handler(context.mainLooper).post {
                all.clear(); all.addAll(apps); render()
            }
        }
        dialog.setOnDismissListener { executor.shutdownNow() }
        dialog.show()
    }
}
