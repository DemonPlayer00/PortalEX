package moe.fuqiuluo.portalex.ui.camera

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.xposed.camera.CameraMockSelector
import moe.fuqiuluo.xposed.camera.CameraVideo
import moe.fuqiuluo.xposed.camera.isStill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 某个视频点下的视频清单：名称 + 时长/标签 + （改标签 / 删除） */
class CameraVideoAdapter(
    private val dataSet: MutableList<CameraVideo>,
    private val onEdit: (CameraVideo) -> Unit,
    private val onDelete: (CameraVideo) -> Unit,
    private val onExport: (CameraVideo) -> Unit,
    private val onRetry: (CameraVideo) -> Unit
) : RecyclerView.Adapter<CameraVideoAdapter.ViewHolder>() {
    private var filterTags: List<String> = emptyList()

    fun setFilterTags(tags: Collection<String>) {
        filterTags = tags.toList()
        notifyDataSetChanged()
    }

    /** 与选片引擎同一条规则（无标签永远命中、有标签全含） */
    private fun matches(video: CameraVideo): Boolean =
        CameraMockSelector.matches(video.tags, filterTags)


    class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val name: MaterialTextView = root.findViewById(R.id.video_name)
        val meta: MaterialTextView = root.findViewById(R.id.video_meta)
        val exportButton: MaterialButton = root.findViewById(R.id.video_export_button)
        val deleteButton: MaterialButton = root.findViewById(R.id.video_delete_button)
        val retryButton: MaterialButton = root.findViewById(R.id.video_retry_button)
    }

    fun replaceAll(items: List<CameraVideo>) {
        dataSet.clear()
        dataSet.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_camera_video, parent, false))

    override fun getItemCount(): Int = dataSet.size

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val video = dataSet[position]
        holder.name.text = video.name
        val time = if (video.recordedAt > 0L) {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(video.recordedAt))
        } else {
            "导入"
        }
        holder.meta.text = buildString {
            append(CameraMockFormat.mediaKind(video))
            append(" · ").append(time)
            if (video.isStill) {
                // 图片的价值在"它绑在哪个点"，所以这里显示绑定坐标而不是轨迹点数
                CameraMockFormat.boundPoint(video)?.let {
                    append(" · 绑定 ").append(CameraMockFormat.coord(it.first, it.second))
                }
            } else if (video.track.isNotEmpty()) {
                append(" · 轨迹 ").append(video.track.size).append(" 点")
            }
            append(" · 标签：").append(CameraMockFormat.tags(video.tags))
        }
        holder.itemView.alpha = if (matches(video)) 1f else 0.45f
        holder.itemView.setOnClickListener { onEdit(video) }
        holder.exportButton.setOnClickListener { onExport(video) }
        holder.deleteButton.setOnClickListener { onDelete(video) }
        holder.retryButton.visibility =
            if (CameraMockUploadState.needsRetry(holder.itemView.context, video.id)) View.VISIBLE else View.GONE
        holder.retryButton.setOnClickListener { onRetry(video) }
    }
}
