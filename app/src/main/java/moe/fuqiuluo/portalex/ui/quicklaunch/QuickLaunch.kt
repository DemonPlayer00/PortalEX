package moe.fuqiuluo.portalex.ui.quicklaunch

import android.content.Context
import android.graphics.Color
import android.location.LocationManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.ext.StaminaPrefs
import moe.fuqiuluo.portalex.ui.camera.cameraMockEnabled
import moe.fuqiuluo.portalex.ext.selectLocation
import moe.fuqiuluo.portalex.ext.selectRoute
import moe.fuqiuluo.portalex.ext.sharedPrefs
import moe.fuqiuluo.portalex.service.ConfigSync
import moe.fuqiuluo.portalex.service.MockServiceHelper

/** 首页快速启动：完全体预设 + 自定义复选框配置。 */
object QuickLaunch {
    private const val PREF = "quick_configs"
    private const val FULL = "__full__"
    val ALL = listOf("location", "route", "gnss", "gps", "cell", "wifi", "camera", "stamina", "cadence", "orientation")
    fun label(ctx: Context, id: String): String = when (id) {
        "location" -> ctx.getString(R.string.quick_location)
        "gnss" -> ctx.getString(R.string.quick_gnss)
        "gps" -> ctx.getString(R.string.quick_gps)
        "cell" -> ctx.getString(R.string.quick_cell)
        "wifi" -> ctx.getString(R.string.quick_wifi)
        "route" -> ctx.getString(R.string.quick_route)
        "camera" -> ctx.getString(R.string.quick_camera)
        "stamina" -> ctx.getString(R.string.quick_stamina)
        "cadence" -> ctx.getString(R.string.quick_cadence)
        else -> ctx.getString(R.string.quick_orientation)
    }

    fun show(ctx: Context) {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.quick_title); textSize = 20f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_input_add)
            contentDescription = ctx.getString(R.string.quick_add)
            setOnClickListener { edit(ctx) }
        })
        root.addView(header)

        root.addView(Button(ctx).apply {
            text = ctx.getString(R.string.quick_full)
            setOnClickListener { apply(ctx, ALL.toSet()) }
        })

        val recycler = RecyclerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 600)
            layoutManager = LinearLayoutManager(ctx)
        }
        root.addView(recycler)
        val adapter = ConfigAdapter(ctx, load(ctx)) { apply(ctx, it.ids) }
        recycler.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val item = adapter.items[vh.adapterPosition]
                adapter.items.removeAt(vh.adapterPosition)
                adapter.notifyItemRemoved(vh.adapterPosition)
                save(ctx, adapter.items)
            }
        }).attachToRecyclerView(recycler)

        MaterialAlertDialogBuilder(ctx)
            .setView(root)
            .setNegativeButton(R.string.quick_close, null)
            .show()
    }

    private fun edit(ctx: Context) {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val checks = ALL.map { id ->
            CheckBox(ctx).apply { text = label(ctx, id); isChecked = true; tag = id }
        }
        checks.forEach { root.addView(it) }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.quick_select)
            .setView(root)
            .setPositiveButton(R.string.quick_save) { _, _ ->
                val ids = checks.filter { it.isChecked }.map { it.tag as String }.toSet()
                if (ids.isNotEmpty()) {
                    val items = load(ctx).toMutableList()
                    items += Config(ctx.getString(R.string.quick_config_name, items.size + 1), ids)
                    save(ctx, items)
                }
            }
            .setNegativeButton(R.string.quick_discard, null)
            .create()
        dialog.show()
    }

    fun validate(ctx: Context, ids: Set<String>): String? {
        val hasRoute = ctx.selectRoute != null
        if ("route" in ids && !hasRoute) return ctx.getString(R.string.quick_need_route)
        if ("camera" in ids && ctx.selectLocation == null && !hasRoute) return ctx.getString(R.string.quick_need_location)
        if ("location" in ids && ctx.selectLocation == null && !hasRoute) return ctx.getString(R.string.quick_need_location)
        return null
    }

    fun apply(ctx: Context, ids: Set<String>, onProgress: ((String) -> Unit)? = null): String? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return ctx.getString(R.string.quick_no_service)
        if (ids.isNotEmpty()) {
            validate(ctx, ids)?.let { return it }
        }
        runCatching { MockServiceHelper.tryInitService(lm) }

        val wantCamera = "camera" in ids
        val wantLocation = "location" in ids || wantCamera

        onProgress?.invoke(ctx.getString(if (ids.isEmpty()) R.string.quick_stopping else R.string.quick_validating))
        if (!wantCamera) ctx.cameraMockEnabled = false

        if (wantLocation) {
            onProgress?.invoke(ctx.getString(R.string.quick_starting_location))
            runCatching { MockServiceHelper.tryOpenMock(lm, 3.05, 80.0, 25f) }
        } else {
            runCatching { MockServiceHelper.tryCloseMock(lm) }
        }

        if ("gnss" in ids) {
            onProgress?.invoke(ctx.getString(R.string.quick_starting_gnss))
            runCatching { MockServiceHelper.startGnssMock(lm) }
        } else {
            runCatching { MockServiceHelper.stopGnssMock(lm) }
        }

        val gpsOn = "gps" in ids
        if (gpsOn) onProgress?.invoke(ctx.getString(R.string.quick_starting_gps))
        val gpsV1 = ctx.sharedPrefs.getString("sim_gps_v1", "3").orEmpty()
        val gpsV2 = ctx.sharedPrefs.getString("sim_gps_v2", "12").orEmpty()
        runCatching { MockServiceHelper.setSimConfig(lm, "gps", gpsOn, gpsV1, gpsV2) }

        val cellOn = "cell" in ids
        val cellV1 = ctx.sharedPrefs.getString("sim_cell_v1", "460/00").orEmpty()
        val cellV2 = ctx.sharedPrefs.getString("sim_cell_v2", "123456789/1234").orEmpty()
        runCatching { MockServiceHelper.setSimConfig(lm, "cell", cellOn, cellV1, cellV2) }

        val wifiOn = "wifi" in ids
        val wifiV1 = ctx.sharedPrefs.getString("sim_wifi_v1", "CMCC-EDU").orEmpty()
        val wifiV2 = ctx.sharedPrefs.getString("sim_wifi_v2", "02:00:00:00:00:00").orEmpty()
        runCatching { MockServiceHelper.setSimConfig(lm, "wifi", wifiOn, wifiV1, wifiV2) }

        onProgress?.invoke(ctx.getString(R.string.quick_starting_sensors))
        runCatching { ConfigSync.setSensorMock(ctx, lm, "cadence" in ids, "orientation" in ids) }
        runCatching {
            val cfg = StaminaPrefs.load(ctx).copy(enabled = "stamina" in ids)
            StaminaPrefs.save(ctx, cfg)
            ConfigSync.push(ctx, lm)
        }

        if (wantCamera) {
            onProgress?.invoke(ctx.getString(R.string.quick_starting_camera))
            // 让位置会话的第一次出帧先落地，再启用依赖坐标的相机。
            runCatching { Thread.sleep(300) }
            ctx.cameraMockEnabled = true
        }
        onProgress?.invoke(ctx.getString(R.string.quick_done))
        return null
    }

    data class Config(val name: String, val ids: Set<String>)
    fun load(ctx: Context): MutableList<Config> =
        ctx.sharedPrefs.getStringSet(PREF, emptySet()).orEmpty().mapNotNull { raw ->
            val parts = raw.split("|", limit = 2)
            if (parts.size != 2) null else Config(parts[0], parts[1].split(",").filter { it.isNotBlank() }.toSet())
        }.toMutableList()
    fun save(ctx: Context, items: List<Config>) {
        ctx.sharedPrefs.edit().putStringSet(PREF, items.map { "${it.name}|${it.ids.joinToString(",")}" }.toSet()).apply()
    }

    private class ConfigAdapter(
        private val ctx: Context,
        val items: MutableList<Config>,
        private val onClick: (Config) -> Unit,
    ) : RecyclerView.Adapter<ConfigAdapter.VH>() {
        class VH(val view: TextView) : RecyclerView.ViewHolder(view)
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(TextView(parent.context).apply { setPadding(24, 24, 24, 24); textSize = 16f })
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.view.text = "${item.name}  (${item.ids.size})"
            holder.view.setOnClickListener { onClick(item) }
        }
        override fun getItemCount() = items.size
    }
}
