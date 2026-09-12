package moe.fuqiuluo.portalex.ui.camera

import android.content.Context
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.xposed.camera.CameraTags

/**
 * 标签编辑对话框：预设（晴/阴/白天/黑夜）+ 库内已有的自定义标签 + 现场新增。
 *
 * 为什么不限制在预设里：用户口径是"【其它自定义标签】"，所以这里是**开放集合** ——
 * 预设只是常用项的快捷方式。新增的标签只要有人用过，下次打开就会出现在自定义组里
 * （[libraryTags] 由调用方从库里取）。
 */
object CameraTagDialog {

    fun show(
        context: Context,
        selected: List<String>,
        libraryTags: List<String>,
        onConfirm: (List<String>) -> Unit
    ): AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_camera_tags, null)
        val presetGroup: ChipGroup = view.findViewById(R.id.camera_tag_presets)
        val customGroup: ChipGroup = view.findViewById(R.id.camera_tag_custom)
        val input: TextInputEditText = view.findViewById(R.id.camera_tag_input)

        val chosen = LinkedHashSet<String>()
        selected.forEach { chosen.add(it) }

        fun addChip(group: ChipGroup, tag: String) {
            val chip = LayoutInflater.from(context)
                .inflate(R.layout.item_camera_tag_chip, group, false) as Chip
            chip.text = tag
            chip.isChecked = chosen.any { it.equals(tag, ignoreCase = true) }
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) chosen.add(tag) else chosen.removeAll { it.equals(tag, ignoreCase = true) }
            }
            group.addView(chip)
        }

        CameraTags.PRESETS.forEach { addChip(presetGroup, it) }
        val custom = libraryTags.filterNot { tag -> CameraTags.PRESETS.any { it.equals(tag, true) } }
        if (custom.isEmpty()) {
            view.findViewById<android.view.View>(R.id.camera_tag_custom_label)?.visibility = android.view.View.GONE
        }
        custom.forEach { addChip(customGroup, it) }

        fun commitNewTag() {
            val raw = input.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) return
            val tag = CameraTags.sanitize(listOf(raw)).firstOrNull() ?: return
            // 先记账再找控件：新增的胶囊由 addChip 按 chosen 决定勾选态，天然就是勾上的
            chosen.add(tag)
            val allChips = (0 until presetGroup.childCount).map { presetGroup.getChildAt(it) as Chip } +
                    (0 until customGroup.childCount).map { customGroup.getChildAt(it) as Chip }
            val existing = allChips.firstOrNull { it.text.toString().equals(tag, ignoreCase = true) }
            if (existing != null) {
                existing.isChecked = true
            } else {
                addChip(customGroup, tag)
                (customGroup.getChildAt(customGroup.childCount - 1) as? Chip)?.isChecked = true
                customGroup.visibility = android.view.View.VISIBLE
                view.findViewById<android.view.View>(R.id.camera_tag_custom_label)?.visibility =
                    android.view.View.VISIBLE
            }
            input.setText("")
        }

        view.findViewById<android.view.View>(R.id.camera_tag_add).setOnClickListener { commitNewTag() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitNewTag()
                true
            } else false
        }

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.camera_video_tags)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ -> onConfirm(CameraTags.sanitize(chosen)) }
            .setNegativeButton(R.string.cancel, null)
            // 点空白不关闭：标签是逐个勾出来的，误触外面丢掉选择体验很差
            .create()
            .also { it.setCanceledOnTouchOutside(false); it.show() }
    }
}
