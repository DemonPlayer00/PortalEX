package moe.fuqiuluo.portalex.ui.quicklaunch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.databinding.FragmentQuickLaunchBinding
import moe.fuqiuluo.portalex.databinding.ItemQuickConfigBinding

class QuickLaunchFragment : Fragment() {
    private var _binding: FragmentQuickLaunchBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: Adapter
    private var checkedName: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentQuickLaunchBinding.inflate(inflater, container, false)
        binding.quickList.layoutManager = LinearLayoutManager(requireContext())
        adapter = Adapter(buildItems())
        binding.quickList.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.adapterPosition
                if (pos <= 0) { adapter.notifyItemChanged(pos); return }
                val item = adapter.items.removeAt(pos)
                adapter.notifyItemRemoved(pos)
                if (checkedName == item.config.name) {
                    checkedName = null
                    applyWithProgress(item, false)
                }
                QuickLaunch.save(requireContext(), adapter.items.filter { !it.full }.map { it.config })
            }
        }).attachToRecyclerView(binding.quickList)
        return binding.root
    }

    private fun buildItems(): MutableList<Row> {
        val rows = mutableListOf(Row(QuickLaunch.Config("__full__", QuickLaunch.ALL.toSet()), true))
        QuickLaunch.load(requireContext()).forEach { rows += Row(it, false) }
        return rows
    }

    fun showAdd() {
        val ctx = requireContext()
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val checks = QuickLaunch.ALL.map { id ->
            CheckBox(ctx).apply {
                text = QuickLaunch.label(ctx, id); isChecked = true; tag = id
            }
        }
        val list = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        checks.forEach { list.addView(it) }
        // 给标题和底部按钮留出足够空间：内容区最多约 38% 屏高，宁可多滚一点也不能把按钮顶出窗口。
        val maxHeight = (resources.displayMetrics.heightPixels * 0.38f).toInt()
            .coerceAtMost((resources.displayMetrics.density * 360).toInt())
        root.addView(android.widget.ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, maxHeight
            )
            addView(list)
        })
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.quick_select)
            .setView(root)
            .setPositiveButton(R.string.quick_save) { _, _ ->
                val ids = checks.filter { it.isChecked }.map { it.tag as String }.toSet()
                if (ids.isNotEmpty()) {
                    val name = getString(R.string.quick_config_name, adapter.items.size)
                    adapter.items += Row(QuickLaunch.Config(name, ids), false)
                    adapter.notifyItemInserted(adapter.items.lastIndex)
                    QuickLaunch.save(requireContext(), adapter.items.filter { !it.full }.map { it.config })
                }
            }
            .setNegativeButton(R.string.quick_discard, null)
            .show()
    }

    private fun applyWithProgress(row: Row, enable: Boolean) {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(48, 28, 48, 28)
        }
        root.addView(CircularProgressIndicator(ctx).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(48, 48)
        })
        val text = TextView(ctx).apply {
            text = getString(if (enable) R.string.quick_validating else R.string.quick_stopping)
            textSize = 14f
            setPadding(24, 0, 0, 0)
        }
        root.addView(text)
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(root)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                QuickLaunch.apply(ctx, if (enable) row.config.ids else emptySet()) { msg ->
                    activity?.runOnUiThread { if (dialog.isShowing) text.text = msg }
                }
            }
            if (!isAdded) return@launch
            dialog.dismiss()
            if (error != null) {
                checkedName = null
                adapter.notifyDataSetChanged()
                Toast.makeText(ctx, error, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, if (enable) R.string.quick_applied else R.string.quick_stopped, Toast.LENGTH_SHORT).show()
                if (!enable) adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    data class Row(val config: QuickLaunch.Config, val full: Boolean)

    private inner class Adapter(val items: MutableList<Row>) : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val b: ItemQuickConfigBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemQuickConfigBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            holder.b.quickCheck.setOnCheckedChangeListener(null)
            holder.b.quickCheck.isChecked = row.config.name == checkedName
            holder.b.quickCheck.text = if (row.full) getString(R.string.quick_full) else row.config.name
            holder.b.quickCheck.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    checkedName = row.config.name
                    notifyDataSetChanged()
                    applyWithProgress(row, true)
                } else if (checkedName == row.config.name) {
                    checkedName = null
                    applyWithProgress(row, false)
                }
            }
        }
        override fun getItemCount() = items.size
    }
}
