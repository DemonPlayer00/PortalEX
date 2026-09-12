package moe.fuqiuluo.portalex.ui.camera

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.xposed.camera.CameraVideo

/** 某个视频点下的视频清单：名称 + 时长/标签 + （改标签 / 删除） */
class CameraVideoAdapter(
    private val dataSet: MutableList<CameraVideo>,
    private val onEditTags: (CameraVideo) -> Unit,
    private val onDelete: (CameraVideo) -> Unit
) : RecyclerView.Adapter<CameraVideoAdapter.ViewHolder>() {

    class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val name: MaterialTextView = root.findViewById(R.id.video_name)
        val meta: MaterialTextView = root.findViewById(R.id.video_meta)
        val tagsButton: MaterialButton = root.findViewById(R.id.video_tags_button)
        val deleteButton: MaterialButton = root.findViewById(R.id.video_delete_button)
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
        holder.meta.text = "时长 ${CameraMockFormat.duration(video.durationMs)} · 标签：${CameraMockFormat.tags(video.tags)}"
        holder.tagsButton.setOnClickListener { onEditTags(video) }
        holder.deleteButton.setOnClickListener { onDelete(video) }
    }
}
