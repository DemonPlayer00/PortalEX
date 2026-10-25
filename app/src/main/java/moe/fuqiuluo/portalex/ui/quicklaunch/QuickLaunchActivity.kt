package moe.fuqiuluo.portalex.ui.quicklaunch

import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.databinding.ActivityQuickLaunchBinding

class QuickLaunchActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQuickLaunchBinding
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickLaunchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.quickToolbar.setNavigationOnClickListener { finish() }
        binding.quickToolbar.inflateMenu(R.menu.menu_quick_launch)
        binding.quickToolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_quick_add) { showAdd(); true } else false
        }
        binding.quickFull.setOnClickListener { QuickLaunch.apply(this, QuickLaunch.ALL.toSet()) }
        binding.quickList.layoutManager = LinearLayoutManager(this)
        adapter = Adapter(QuickLaunch.load(this)) { QuickLaunch.apply(this, it.ids) }
        binding.quickList.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.adapterPosition
                adapter.items.removeAt(pos)
                adapter.notifyItemRemoved(pos)
                QuickLaunch.save(this@QuickLaunchActivity, adapter.items)
            }
        }).attachToRecyclerView(binding.quickList)
    }

    private fun showAdd() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val checks = QuickLaunch.ALL.map { id ->
            CheckBox(this).apply { text = QuickLaunch.label(this@QuickLaunchActivity, id); isChecked = true; tag = id }
        }
        checks.forEach { root.addView(it) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quick_select)
            .setView(root)
            .setPositiveButton(R.string.quick_save) { _, _ ->
                val ids = checks.filter { it.isChecked }.map { it.tag as String }.toSet()
                if (ids.isNotEmpty()) {
                    adapter.items += QuickLaunch.Config(
                        getString(R.string.quick_config_name, adapter.items.size + 1), ids
                    )
                    adapter.notifyItemInserted(adapter.items.lastIndex)
                    QuickLaunch.save(this, adapter.items)
                }
            }
            .setNegativeButton(R.string.quick_discard, null)
            .show()
    }

    private class Adapter(
        val items: MutableList<QuickLaunch.Config>,
        private val onClick: (QuickLaunch.Config) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {
        class VH(val view: TextView) : RecyclerView.ViewHolder(view)
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(TextView(parent.context).apply {
                setPadding(32, 28, 32, 28); textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
            })
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.view.text = "${item.name}  (${item.ids.size})"
            holder.view.setOnClickListener { onClick(item) }
        }
        override fun getItemCount() = items.size
    }
}
