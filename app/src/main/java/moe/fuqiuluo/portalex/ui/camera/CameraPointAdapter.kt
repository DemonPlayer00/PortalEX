package moe.fuqiuluo.portalex.ui.camera

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textview.MaterialTextView
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.xposed.camera.CameraVideoPoint

/** 视频点列表：名称 + 坐标/视频数 + 标签汇总；点一行进入该点的视频编辑 */
class CameraPointAdapter(
    private val dataSet: MutableList<CameraVideoPoint>,
    private val onItemClicked: (CameraVideoPoint) -> Unit
) : RecyclerView.Adapter<CameraPointAdapter.ViewHolder>() {
    private var filterTags: Set<String> = emptySet()

    fun setFilterTags(tags: Set<String>) {
        filterTags = tags.map { it.lowercase() }.toSet()
        notifyDataSetChanged()
    }

    private fun matches(point: CameraVideoPoint): Boolean =
        point.videos.any { v ->
            v.tags.isEmpty() || filterTags.all { t -> v.tags.any { it.equals(t, true) } }
        }


    class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val name: MaterialTextView = root.findViewById(R.id.point_name)
        val summary: MaterialTextView = root.findViewById(R.id.point_summary)
        val tags: MaterialTextView = root.findViewById(R.id.point_tags)
    }

    operator fun get(position: Int): CameraVideoPoint = dataSet[position]

    fun replaceAll(items: List<CameraVideoPoint>) {
        dataSet.clear()
        dataSet.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_camera_point, parent, false))

    override fun getItemCount(): Int = dataSet.size

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val point = dataSet[position]
        holder.name.text = point.name
        holder.summary.text = CameraMockFormat.pointSummary(point)
        // 点位标签 = 其名下视频标签的并集：列表页一眼能看出"这个点上有什么天气"
        val tags = point.videos.flatMap { it.tags }.distinct()
        // 路线集合不需要“无标签”占位：没有标签就不显示这一行。
        if (tags.isEmpty()) {
            holder.tags.visibility = View.GONE
        } else {
            holder.tags.visibility = View.VISIBLE
            holder.tags.text = CameraMockFormat.tags(tags)
        }
        holder.itemView.alpha = if (matches(point)) 1f else 0.45f
        holder.itemView.setOnClickListener { onItemClicked(point) }
    }
}
