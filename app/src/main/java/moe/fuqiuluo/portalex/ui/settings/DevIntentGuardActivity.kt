package moe.fuqiuluo.portalex.ui.settings

import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import moe.fuqiuluo.portalex.ext.devIntentGuardEnabled
import moe.fuqiuluo.portalex.ext.devIntentGuardPackages
import moe.fuqiuluo.portalex.service.MockServiceHelper
import java.util.concurrent.Executors

/** 阻止指定应用启动开发者选项页面（按包名，system_server 侧执行）。 */
class DevIntentGuardActivity : AppCompatActivity() {
    private data class AppEntry(val pkg: String, val label: String)

    private lateinit var guardSwitch: SwitchMaterial
    private lateinit var search: EditText
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private val all = ArrayList<AppEntry>()
    private val selected = LinkedHashSet<String>()
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected += devIntentGuardPackages

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        root.addView(TextView(this).apply {
            text = "阻止开发者 Intent"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "开启后，选中的应用无法启动开发者选项页面。关闭时列表只读并灰化。"
            textSize = 13f
        })
        guardSwitch = SwitchMaterial(this).apply {
            text = "启用拦截"
            isChecked = devIntentGuardEnabled
            setPadding(0, 16, 0, 8)
        }
        root.addView(guardSwitch)
        summary = TextView(this).apply { textSize = 12f }
        root.addView(summary)
        search = EditText(this).apply {
            hint = "搜索应用名或包名"
            setSingleLine(true)
        }
        root.addView(search)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        guardSwitch.setOnCheckedChangeListener { _, checked ->
            devIntentGuardEnabled = checked
            render()
            push()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        loadApps()
    }

    private fun loadApps() {
        summary.text = "正在读取应用列表…"
        executor.execute {
            val pm = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
                .toList()
            runOnUiThread {
                all.clear(); all.addAll(apps); render()
            }
        }
    }

    private fun render() {
        if (!::list.isInitialized) return
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        list.removeAllViews()
        val enabled = guardSwitch.isChecked
        for (app in all) {
            if (q.isNotEmpty() && !app.label.lowercase().contains(q) && !app.pkg.lowercase().contains(q)) continue
            val cb = CheckBox(this).apply {
                text = "${app.label}  (${app.pkg})"
                isChecked = app.pkg in selected
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.4f
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected += app.pkg else selected -= app.pkg
                    devIntentGuardPackages = selected
                    push()
                }
            }
            list.addView(cb)
        }
        summary.text = "已选择 ${selected.size} 个应用" + if (!enabled) "（拦截已关闭）" else ""
    }

    private fun push() {
        val lm = getSystemService(LocationManager::class.java) ?: return
        val enabled = guardSwitch.isChecked
        val pkgs = selected.toSet()
        executor.execute { MockServiceHelper.setDevIntentGuard(lm, enabled, pkgs) }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
