package moe.fuqiuluo.portalex.ui.common

import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.ext.shiftAboveIme

/**
 * **数值设置行**的共用实现（模板 `item_value_row.xml` + 输入对话框 `dialog_input.xml`）。
 *
 * 为什么抽出来：体力 / 步频 / 角度指南针三个页面都是"一行 = 一个可点改的数值"，
 * 各写一份必然漂移 —— 本项目已经吃过"复制出来的行漏改 id"的亏（见体力页顶部注释）。
 * 于是行模板与对话框都只留**一处**。
 *
 * 行为约定（与体力页原来的实现逐条一致）：
 *  · 点整行弹输入框，预填**当前原始数值**（不是显示文本，显示文本带单位）；
 *  · 只有点「保存」且能解析成数字才回调；取消/乱填一律不改；
 *  · 对话框在输入法弹出时上移（[shiftAboveIme]），避免被键盘盖住。
 */
class NumberRow(
    val title: String,
    /** 行下方的说明 */
    val desc: String,
    /** 右侧显示的当前值文本（带单位） */
    val display: () -> String,
    /** 弹框预填用的原始数值 */
    val current: () -> Double,
    /** 弹框里的长说明 */
    val hint: () -> String,
    /** 用户填了新值并保存 */
    val commit: (Double) -> Unit,
)

/**
 * 一行可点改的**文本**设置（用于"x/y/z"这类多值行，例如磁场/方向角/旋转矢量噪声）。
 * 与 [NumberRow] 共用同一模板与同一对话框，只是不做数字解析 —— 解析交给行自己的 [commit]。
 */
class TextRow(
    val title: String,
    val desc: String,
    val display: () -> String,
    val current: () -> String,
    val hint: () -> String,
    val commit: (String) -> Unit,
)

/** 按 [rows] 重铺容器里的行（数字行） */
fun renderNumberRows(
    inflater: LayoutInflater,
    container: LinearLayout,
    rows: List<NumberRow>,
) {
    container.removeAllViews()
    rows.forEach { row ->
        val view = inflater.inflate(R.layout.item_value_row, container, false)
        view.findViewById<TextView>(R.id.value_row_title).text = row.title
        view.findViewById<TextView>(R.id.value_row_desc).text = row.desc
        view.findViewById<TextView>(R.id.value_row_value).text = row.display()
        view.setOnClickListener { anchor ->
            showNumberDialog(anchor, row.title, row.current(), row.hint()) { value ->
                row.commit(value)
            }
        }
        container.addView(view)
    }
}

/** 按 [rows] 重铺容器里的行（文本行；多值行用它） */
fun renderTextRows(
    inflater: LayoutInflater,
    container: LinearLayout,
    rows: List<TextRow>,
) {
    container.removeAllViews()
    rows.forEach { row ->
        val view = inflater.inflate(R.layout.item_value_row, container, false)
        view.findViewById<TextView>(R.id.value_row_title).text = row.title
        view.findViewById<TextView>(R.id.value_row_desc).text = row.desc
        view.findViewById<TextView>(R.id.value_row_value).text = row.display()
        view.setOnClickListener { anchor ->
            showInputDialog(anchor, row.title, row.current(), row.hint()) { text -> row.commit(text) }
        }
        container.addView(view)
    }
}

/** 单数值输入框：预填原始数值，只有能解析成数字才回调 */
fun showNumberDialog(
    anchor: View,
    titleText: String,
    current: Double,
    hint: String,
    onValue: (Double) -> Unit,
) {
    // 整数就不显示 ".0"（用户改的是"11"不是"11.0"），与体力页原实现一致
    val text = if (current == current.toLong().toDouble()) current.toLong().toString() else current.toString()
    showInputDialog(anchor, titleText, text, hint) { input ->
        input.trim().toDoubleOrNull()?.let(onValue)
    }
}

/**
 * 输入对话框的**唯一实现**（数字行与文本行都走它）。
 * **锚点视图**用来找宿主 Activity 的 decorView（输入法上移要它），调用方不必传 Activity。
 */
fun showInputDialog(
    anchor: View,
    titleText: String,
    currentText: String,
    hint: String,
    onText: (String) -> Unit,
) {
    val context = anchor.context
    val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_input, null)
    dialogView.findViewById<TextView>(R.id.title).text = titleText
    val value = dialogView.findViewById<TextInputEditText>(R.id.value)
    value.setText(currentText)

    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(null)
        .setMessage(hint)
        .setView(dialogView)
        .setPositiveButton("保存") { _, _ -> onText(value.text.toString()) }
        .setNegativeButton("取消", null)
        .show()
    activityOf(context)?.let { dialog.shiftAboveIme(it.window.decorView) }
}

/** 从 Context 往上找宿主 Activity（LayoutInflater 的 context 通常是 ContextThemeWrapper） */
private fun activityOf(context: Context): AppCompatActivity? {
    var ctx: Context? = context
    while (ctx is ContextWrapper) {
        if (ctx is AppCompatActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
