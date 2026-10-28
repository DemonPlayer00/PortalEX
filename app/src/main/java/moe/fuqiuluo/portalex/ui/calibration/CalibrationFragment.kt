package moe.fuqiuluo.portalex.ui.calibration

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.portalex.R
import moe.fuqiuluo.portalex.android.widget.SensorTraceChart
import moe.fuqiuluo.portalex.databinding.FragmentCalibrationBinding
import moe.fuqiuluo.portalex.ext.accuracy
import moe.fuqiuluo.portalex.ext.altitude
import moe.fuqiuluo.portalex.ext.altitudeJitter
import moe.fuqiuluo.portalex.ext.fusedAccuracyMeasured
import moe.fuqiuluo.portalex.ext.fusedDelayMeasured
import moe.fuqiuluo.portalex.ext.gpsAccuracyMeasured
import moe.fuqiuluo.portalex.ext.gpsIntervalMeasured
import moe.fuqiuluo.portalex.ext.maxCn0Measured
import moe.fuqiuluo.portalex.ext.meanCn0Measured
import moe.fuqiuluo.portalex.ext.minSatelliteCount
import moe.fuqiuluo.portalex.ext.satelliteJitter
import moe.fuqiuluo.portalex.ext.cellEntries
import moe.fuqiuluo.portalex.ext.gnssFlags
import moe.fuqiuluo.portalex.ext.reportDuration
import moe.fuqiuluo.portalex.ext.wifiEntries
import moe.fuqiuluo.portalex.ext.satellitesMeasured
import moe.fuqiuluo.portalex.ext.sensorNoise
import moe.fuqiuluo.portalex.ext.speedAmplitude
import moe.fuqiuluo.portalex.ext.sensorNoiseReport
import moe.fuqiuluo.portalex.ext.sharedPrefs
import moe.fuqiuluo.portalex.ext.shiftAboveIme
import moe.fuqiuluo.portalex.service.ConfigSync
import moe.fuqiuluo.portalex.service.LocationAccuracyCalibrator
import moe.fuqiuluo.portalex.service.MockServiceHelper
import moe.fuqiuluo.portalex.service.SensorNoiseCalibrator
import moe.fuqiuluo.portalex.ui.viewmodel.MockServiceViewModel
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.SensorNoise
import kotlin.math.abs

/**
 * Calibration 页：注入噪声档的**可编辑列表** + **一键校准**。
 *
 * 列表就是 [SensorNoise.ITEMS]（逐轴 σ + 陀螺零偏），点一行改一行；一键校准把真实传感器的
 * 逐轴中位数与方差量出来后回填（只有 σ 与陀螺零偏会写进去，中位数其余部分只作参照显示）。
 *
 * 页面底部那行是**系统侧回读**（`get_sensor_status` 里由原生层回报的噪声档）——用来证明
 * 下发真的落到了 system_server 的原生注入层，而不是只写进了 App 的偏好。
 */
class CalibrationFragment : Fragment() {
    companion object {
        private const val GROUP_SENSOR = 0
        private const val GROUP_LOCATION = 1
        private const val GROUP_BASEBAND = 2
    }

    private var _binding: FragmentCalibrationBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    /** 行 → 该行数值显示控件（顺序与 [SensorNoise.ITEMS] 一致） */
    private val rowValues = mutableListOf<TextView>()

    /** 定位波动三项的数值显示（顺序：精度 / 海拔 / 速度）。 */
    private val fluctuationValues = mutableListOf<TextView>()
    private var gpsAccuracyValue: TextView? = null
    private var fusedAccuracyValue: TextView? = null
    private var fusedDelayValue: TextView? = null
    private var gpsIntervalValue: TextView? = null
    private var satellitesValue: TextView? = null
    private var altitudeValue: TextView? = null
    private var maxCn0Value: TextView? = null
    private var gnssFlagsValue: TextView? = null
    private var cellListValue: TextView? = null
    private var wifiListValue: TextView? = null
    private var meanCn0Value: TextView? = null

    /** 一次校准的令牌：作废的采集（用户取消/页面销毁）不许回写 */
    private var runToken = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCalibrationBinding.inflate(inflater, container, false)
        buildRows()
        buildFluctuationRows()
        renderValues(requireContext().sensorNoise)
        renderFluctuation()
        binding.calibStatus.text = requireContext().sensorNoiseReport
        binding.calibRun.setOnClickListener { showCalibrationDialog() }
        binding.calibReset.setOnClickListener { confirmReset() }
        refreshEcho()
        return binding.root
    }

    // ---- 列表 ----

    private fun buildRows() {
        val inflater = LayoutInflater.from(requireContext())
        val list: LinearLayout = binding.calibList
        SensorNoise.ITEMS.forEachIndexed { index, item ->
            val row = inflater.inflate(R.layout.layout_noise_row, list, false)
            row.findViewById<TextView>(R.id.noise_title).text = item.title
            row.findViewById<TextView>(R.id.noise_desc).text = item.desc
            val value = row.findViewById<TextView>(R.id.noise_value)
            rowValues += value
            row.setOnClickListener { editRow(index) }
            list.addView(row)
        }
    }

    private fun buildFluctuationRows() {
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)
        val list: LinearLayout = binding.calibFluctuationList
        data class Item(val title: String, val desc: String, val action: () -> Unit)
        val items = listOf(
            Item(getString(R.string.accuracy), getString(R.string.calibration_accuracy_desc)) {
                editScalar("定位精度", ctx.accuracy.toDouble(), 0.0, 1000.0) { v ->
                    ctx.accuracy = v.toFloat()
                }
            },
            Item(getString(R.string.calibration_altitude_jitter), getString(R.string.calibration_altitude_jitter_desc)) {
                editScalar("海拔波动", ctx.altitudeJitter, 0.0, 100.0) { v ->
                    ctx.altitudeJitter = v
                }
            },
            Item(getString(R.string.calibration_speed_amplitude), getString(R.string.calibration_speed_amplitude_desc)) {
                editScalar("速度波动", ctx.speedAmplitude, 0.0, 100.0) { v ->
                    ctx.speedAmplitude = v
                }
            },
        )
        items.forEach { item ->
            val row = inflater.inflate(R.layout.layout_noise_row, list, false)
            row.findViewById<TextView>(R.id.noise_title).text = item.title
            row.findViewById<TextView>(R.id.noise_desc).text = item.desc
            val value = row.findViewById<TextView>(R.id.noise_value)
            fluctuationValues += value
            row.setOnClickListener { item.action() }
            list.addView(row)
        }

        fun measurementRow(title: String, desc: String, action: (() -> Unit)? = null): TextView {
            val row = inflater.inflate(R.layout.layout_noise_row, list, false)
            row.findViewById<TextView>(R.id.noise_title).text = title
            row.findViewById<TextView>(R.id.noise_desc).text = desc
            val value = row.findViewById<TextView>(R.id.noise_value)
            value.text = "未采集"
            if (action != null) {
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener { action() }
            } else {
                row.isClickable = false
                row.isFocusable = false
            }
            list.addView(row)
            return value
        }
        gpsAccuracyValue = measurementRow(
            getString(R.string.calibration_gps_accuracy),
            getString(R.string.calibration_gps_accuracy_desc),
        ) {
            editScalar("GPS 定位精度", ctx.gpsAccuracyMeasured.toDouble(), 0.0, 1000.0) { v ->
                ctx.gpsAccuracyMeasured = v.toFloat()
                ctx.accuracy = v.toFloat()
            }
        }
        altitudeValue = measurementRow(
            getString(R.string.calibration_altitude),
            getString(R.string.calibration_altitude_desc),
        ) {
            editScalar("海拔高度", ctx.altitude, -500.0, 10_000.0) { v ->
                ctx.altitude = v
                altitudeValue?.text = "%.1f m".format(v)
            }
        }
        altitudeValue?.text = "%.1f m".format(ctx.altitude)
        fusedAccuracyValue = measurementRow(
            getString(R.string.calibration_fused_accuracy),
            getString(R.string.calibration_fused_accuracy_desc),
        ) {
            editScalar("fused 定位精度", ctx.fusedAccuracyMeasured.toDouble(), 0.0, 1000.0) { v ->
                ctx.fusedAccuracyMeasured = v.toFloat()
            }
        }
        fusedDelayValue = measurementRow(
            getString(R.string.calibration_fused_delay),
            getString(R.string.calibration_fused_delay_desc),
        ) {
            editScalar("fused 时间差", ctx.fusedDelayMeasured.toDouble(), 0.0, 10_000.0) { v ->
                ctx.fusedDelayMeasured = v.toFloat()
            }
        }
        gpsIntervalValue = measurementRow(
            getString(R.string.calibration_gps_interval),
            getString(R.string.calibration_gps_interval_desc),
        ) {
            editScalar("GPS 上报间隔", ctx.gpsIntervalMeasured.toDouble(), 200.0, 2_000.0) { v ->
                ctx.gpsIntervalMeasured = v.toFloat()
                ctx.reportDuration = v.toInt()
            }
        }
        satellitesValue = measurementRow(
            getString(R.string.calibration_satellite_count),
            getString(R.string.calibration_satellite_count_desc),
        ) {
            editSatellite(ctx)
        }
        maxCn0Value = measurementRow(
            getString(R.string.calibration_max_cn0),
            getString(R.string.calibration_max_cn0_desc),
        ) {
            editScalar("最大 C/N0", ctx.maxCn0Measured.toDouble(), 0.0, 100.0) { v ->
                ctx.maxCn0Measured = v.toFloat()
            }
        }
        meanCn0Value = measurementRow(
            getString(R.string.calibration_mean_cn0),
            getString(R.string.calibration_mean_cn0_desc),
        ) {
            editScalar("平均 C/N0", ctx.meanCn0Measured.toDouble(), 0.0, 100.0) { v ->
                ctx.meanCn0Measured = v.toFloat()
            }
        }
        // 与「一键校准」的 GPS / 基带两组一一对应：列出的就是会被采集/下发的那些数据
        gnssFlagsValue = measurementRow(
            getString(R.string.calibration_gps_flags),
            getString(R.string.calibration_gps_flags_desc),
        ) {
            editLines("卫星状态（每行一颗：E/A/U/F/B[,C/N0]）", requireContext().gnssFlags) { lines ->
                requireContext().gnssFlags = lines
                renderFluctuation()
                sendSim(
                    "gps", lines.firstOrNull().orEmpty(),
                    requireContext().sharedPrefs.getString("sim_gps_v2", "12").orEmpty(),
                    lines.ifEmpty { null }
                )
            }
        }
        cellListValue = measurementRow(
            getString(R.string.calibration_cell_list),
            getString(R.string.calibration_cell_list_desc),
        ) {
            editLines("基站列表（每行 mcc/mnc|cid/tac）", requireContext().cellEntries) { lines ->
                requireContext().cellEntries = lines
                renderFluctuation()
                sendSim("cell", lines.firstOrNull()?.substringBefore("|").orEmpty(),
                    lines.firstOrNull()?.substringAfter("|").orEmpty(), lines.ifEmpty { null })
            }
        }
        wifiListValue = measurementRow(
            getString(R.string.calibration_wifi_list),
            getString(R.string.calibration_wifi_list_desc),
        ) {
            editLines("Wi-Fi 列表（每行 ssid|bssid）", requireContext().wifiEntries) { lines ->
                requireContext().wifiEntries = lines
                renderFluctuation()
                sendSim("wifi", lines.firstOrNull()?.substringBefore("|").orEmpty(),
                    lines.firstOrNull()?.substringAfter("|").orEmpty(), lines.ifEmpty { null })
            }
        }
    }

    private fun renderFluctuation() {
        val ctx = requireContext()
        fluctuationValues.getOrNull(0)?.text = "%.2f米".format(ctx.accuracy)
        fluctuationValues.getOrNull(1)?.text = "%.2f米".format(ctx.altitudeJitter)
        fluctuationValues.getOrNull(2)?.text = "%.2f米/秒".format(ctx.speedAmplitude)
        gpsAccuracyValue?.text = ctx.gpsAccuracyMeasured.let {
            if (it > 0f) "%.2f米".format(it) else "未采集"
        }
        fusedAccuracyValue?.text = ctx.fusedAccuracyMeasured.let {
            if (it > 0f) "%.2f米".format(it) else "未采集"
        }
        fusedDelayValue?.text = ctx.fusedDelayMeasured.let {
            if (it > 0f) "%.0fms".format(it) else "未采集"
        }
        gpsIntervalValue?.text = ctx.gpsIntervalMeasured.let {
            if (it > 0f) "%.0fms".format(it) else "未采集"
        }
        satellitesValue?.text = ctx.satellitesMeasured.let {
            if (it > 0f) "%.0f颗 ± %.1f".format(it, ctx.satelliteJitter) else "未采集"
        }
        maxCn0Value?.text = ctx.maxCn0Measured.let {
            if (it > 0f) "%.1fdB-Hz".format(it) else "未采集"
        }
        meanCn0Value?.text = ctx.meanCn0Measured.let {
            if (it > 0f) "%.1fdB-Hz".format(it) else "未采集"
        }
        gnssFlagsValue?.text = ctx.gnssFlags.size.let { if (it > 0) "$it 条" else "未设置" }
        cellListValue?.text = ctx.cellEntries.size.let { if (it > 0) "$it 条" else "未设置" }
        wifiListValue?.text = ctx.wifiEntries.size.let { if (it > 0) "$it 条" else "未设置" }
    }

    private fun applyLocationAccuracy(ctx: android.content.Context, result: LocationAccuracyCalibrator.Result) {
        if (!result.ok) {
            // GPS/fused 项失败不应回滚已完成的噪声档校准；只提示。
            showToast(result.message)
            return
        }
        result.gpsAccuracyMeters?.let {
            ctx.gpsAccuracyMeasured = it
            // GPS 精度 -> 模拟水平抖动/上报精度参数
            ctx.accuracy = it
        }
        result.altitudeMeters?.let {
            ctx.altitude = it
            altitudeValue?.text = "%.1f m".format(it)
        }
        result.fusedAccuracyMeters?.let { ctx.fusedAccuracyMeasured = it }
        result.fusedDelayMs?.let { ctx.fusedDelayMeasured = it }
        result.gpsIntervalMs?.let {
            ctx.gpsIntervalMeasured = it
            // GPS 间隔 -> 模拟出帧间隔（钳到与运行时相同的合理范围）
            ctx.reportDuration = it.toInt().coerceIn(200, 2000)
        }
        result.satellites?.let {
            ctx.satellitesMeasured = it
            // 卫星数中位数 -> GNSS 生成器基准；标准差 -> 波动幅度
            ctx.minSatelliteCount = it.toInt().coerceIn(0, FakeLoc.MAX_SATELLITES)
        }
        result.satellitesJitter?.let {
            ctx.satelliteJitter = it.coerceIn(0f, FakeLoc.MAX_SATELLITES.toFloat())
        }
        result.maxCn0?.let { ctx.maxCn0Measured = it }
        result.meanCn0?.let { ctx.meanCn0Measured = it }
        renderFluctuation()
        pushConfig()
        showToast(result.message)
    }

    /** 多行编辑：每行一条（卫星状态 / 基站列表 / Wi-Fi 列表共用） */
    private fun editLines(title: String, initial: List<String>, save: (List<String>) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_input, null)
        view.findViewById<TextView>(R.id.title).text = title
        val value = view.findViewById<android.widget.EditText>(R.id.value)
        value.setText(initial.joinToString("\n"))
        // 布局里是 singleLine + actionNext：不清掉的话回车会被当成"下一步"，
        // 多行内容会被压成一行（这就是"三项都只有一条"的直接原因）
        value.setSingleLine(false)
        value.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
        value.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        value.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        value.minLines = 4
        value.maxLines = 10
        MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val lines = (value.text?.toString() ?: "")
                    .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                save(lines)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editSatellite(ctx: android.content.Context) {
        val currentCount = ctx.satellitesMeasured.takeIf { it > 0f } ?: ctx.minSatelliteCount.toFloat()
        showInputDialog("模拟卫星数", "%.0f".format(currentCount)) { countText ->
            val count = countText.toIntOrNull()
            if (count == null || count < 0 || count > FakeLoc.MAX_SATELLITES) {
                showToast("请输入 0 ~ ${FakeLoc.MAX_SATELLITES} 之间的卫星数")
                return@showInputDialog
            }
            showInputDialog("卫星数波动", "%.1f".format(ctx.satelliteJitter)) { jitterText ->
                val jitter = jitterText.toDoubleOrNull()
                if (jitter == null || jitter < 0.0 || jitter > FakeLoc.MAX_SATELLITES.toDouble()) {
                    showToast("请输入 0 ~ ${FakeLoc.MAX_SATELLITES} 之间的波动值")
                    return@showInputDialog
                }
                ctx.satellitesMeasured = count.toFloat()
                ctx.minSatelliteCount = count
                ctx.satelliteJitter = jitter.toFloat()
                renderFluctuation()
                pushConfig()
            }
        }
    }

    private fun editScalar(title: String, current: Double, min: Double, max: Double, save: (Double) -> Unit) {
        showInputDialog(title, "%.3f".format(current)) { text ->
            val value = text.toDoubleOrNull()
            if (value == null || value < min || value > max) {
                showToast("请输入 $min ~ $max 之间的数值")
                return@showInputDialog
            }
            save(value)
            renderFluctuation()
            pushConfig()
        }
    }

    private fun renderValues(values: FloatArray) {
        SensorNoise.ITEMS.forEachIndexed { index, item ->
            rowValues.getOrNull(index)?.text = SensorNoise.formatItem(item, values)
        }
    }

    private fun editRow(index: Int) {
        val item = SensorNoise.ITEMS[index]
        val current = requireContext().sensorNoise
        showInputDialog(item.title, SensorNoise.formatItem(item, current)) { text ->
            val parsed = SensorNoise.parseItem(item, text).getOrElse {
                showToast(it.message ?: "输入不合法")
                return@showInputDialog
            }
            val updated = SensorNoise.withItem(current, item, parsed)
            requireContext().sensorNoise = updated
            renderValues(requireContext().sensorNoise)
            pushConfig()
        }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("恢复默认噪声档")
            .setMessage("会丢掉当前所有噪声项（含一键校准的结果），回到内置的通用量级默认值。")
            .setPositiveButton("恢复") { _, _ ->
                requireContext().sensorNoise = SensorNoise.DEFAULTS.copyOf()
                renderValues(requireContext().sensorNoise)
                showToast("已恢复默认噪声档")
                pushConfig()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 网络模拟参数自动填入 ----

    private fun autoFillGps() {
        val ctx = requireContext()
        runCatching {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val satellites = loc?.extras?.getInt("satellites", 0) ?: 0
            ctx.sharedPrefs.edit()
                .putString("sim_gps_v1", "EAUBF")
                .putString("sim_gps_v2", satellites.takeIf { it > 0 }?.toString() ?: "0")
                .apply()
            // 逐星状态：**有 GNSS 信号就按真实卫星采集**（一行一颗），
            // 没信号（拿不到 GnssStatus）才退回一条 EAUBF。
            val collected = collectGnssFlags(ctx)
            val flags = if (collected.isEmpty()) listOf("EAUBF") else collected
            requireContext().gnssFlags = flags
            sendSim("gps", flags.firstOrNull() ?: "EAUBF", flags.size.toString(), flags)
            showToast(if (loc == null) getString(R.string.calibration_auto_no_fix) else getString(R.string.calibration_auto_done))
        }.onFailure { showToast(it.message ?: "GPS") }
    }

    /**
     * 收集**全部**小区（已注册排最前），一条一项：mcc/mnc|cid/tac。
     * 列表非空时模块以列表为准，首条即"当前连接"。
     */
    /**
     * 订阅一次 GNSS 状态，把真实卫星的状态翻译成本项目的行格式：
     * E 星历 / A 年历 / U 参与定位 / F 载波 / B 基带 C/N0，逗号后跟 C/N0。
     *
     * 最多等 timeoutMs：没有回调（无信号 / 无 GNSS 硬件）就返回空列表，由调用方回退 EAUBF。
     */
    private fun collectGnssFlags(ctx: android.content.Context, timeoutMs: Long = 3_000L): List<String> {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return emptyList()
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return emptyList()
        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = java.util.concurrent.atomic.AtomicReference<android.location.GnssStatus?>(null)
        val callback = object : android.location.GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(gnssStatus: android.location.GnssStatus) {
                holder.set(gnssStatus)
                latch.countDown()
            }
        }
        return runCatching {
            lm.registerGnssStatusCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            val s = holder.get() ?: return emptyList()
            if (s.satelliteCount <= 0) return emptyList()
            (0 until s.satelliteCount).map { i ->
                val letters = StringBuilder()
                if (s.hasEphemerisData(i)) letters.append('E')
                if (s.hasAlmanacData(i)) letters.append('A')
                if (s.usedInFix(i)) letters.append('U')
                if (s.getCarrierFrequencyHz(i) > 0f) letters.append('F')
                if (s.hasBasebandCn0DbHz(i)) letters.append('B')
                "%s,%.1f".format(letters.toString(), s.getCn0DbHz(i))
            }
        }.getOrElse { emptyList() }.also {
            runCatching { lm.unregisterGnssStatusCallback(callback) }
        }
    }

    private fun autoFillCell() {
        val ctx = requireContext()
        runCatching {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val all = tm.allCellInfo.orEmpty().sortedByDescending { it.isRegistered }
            val entries = LinkedHashSet<String>()
            all.take(16).forEach { info ->
                val id = info.cellIdentity
                val mcc: String?
                val mnc: String?
                val cid: String?
                val tac: String?
                when (id) {
                    is CellIdentityLte -> { mcc = id.mccString; mnc = id.mncString; cid = id.ci.toString(); tac = id.tac.toString() }
                    is CellIdentityGsm -> { mcc = id.mccString; mnc = id.mncString; cid = id.cid.toString(); tac = id.lac.toString() }
                    is CellIdentityNr -> { mcc = id.mccString; mnc = id.mncString; cid = id.nci.toString(); tac = id.tac.toString() }
                    else -> { mcc = null; mnc = null; cid = null; tac = null }
                }
                if (mcc != null && cid != null) entries += "$mcc/$mnc|$cid/$tac"
            }
            if (entries.isEmpty()) {
                showToast(getString(R.string.calibration_auto_no_cell))
                return
            }
            val list = entries.toList()
            ctx.cellEntries = list
            val first = list.first().split("|", limit = 2)
            val v1 = first.getOrNull(0).orEmpty()
            val v2 = first.getOrNull(1).orEmpty()
            ctx.sharedPrefs.edit().putString("sim_cell_v1", v1).putString("sim_cell_v2", v2).apply()
            sendSim("cell", v1, v2, list)
            showToast(getString(R.string.calibration_cell_collected, list.size))
        }.onFailure { showToast(it.message ?: "Cell") }
    }

    /**
     * 收集当前连接 + 附近扫描到的 AP：一条一项 ssid|bssid。
     * 首条是当前连接，模块据此把它当作"已连接"的那一个。
     */
    /** 无定位权限/无定位时系统给的占位 BSSID：不应该被写进配置 */
    private val PLACEHOLDER_BSSID = "02:00:00:00:00:00"

    private fun autoFillWifi() {
        val ctx = requireContext()
        // Android 9+：Wi-Fi 扫描结果与 SSID 都受**定位开关**约束。
        // 定位没开时 scanResults 恒为空、SSID 恒为 <unknown ssid>、BSSID 是 02:00:… 占位，
        // 直接采会写出 "|02:00:00:00:00:00" 这种畸形条目 —— 所以先拦一道。
        val locationOn = runCatching {
            (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
        }.getOrDefault(false)
        if (!locationOn) {
            showToast("定位未开启：Wi-Fi 扫描与 SSID 不可见，请先打开定位再校准")
            return
        }
        runCatching {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val entries = LinkedHashSet<String>()
            val ssid = info?.ssid?.trim('"').orEmpty().takeIf { it != "<unknown ssid>" }.orEmpty()
            val bssid = info?.bssid.orEmpty().takeIf { it != PLACEHOLDER_BSSID }.orEmpty()
            if (ssid.isNotBlank() || bssid.isNotBlank()) entries += "$ssid|$bssid"
            // 扫描是**异步**的：startScan 立刻返回，结果要等系统回调/下次读取。
            // 旧实现在同一个瞬间就读 scanResults，几乎必然是空的（只能拿到"当前连接"那一条）。
            runCatching { wm.startScan() }
            var scans: List<android.net.wifi.ScanResult> = emptyList()
            val deadline = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < deadline) {
                @Suppress("DEPRECATION", "MissingPermission")
                scans = runCatching { wm.scanResults }.getOrNull().orEmpty()
                if (scans.isNotEmpty()) break
                Thread.sleep(300)
            }
            scans.take(16).forEach { ap ->
                val s = ap.SSID.orEmpty()
                val b = ap.BSSID.orEmpty()
                if (b == PLACEHOLDER_BSSID) return@forEach
                if (s.isNotBlank() && s != "<unknown ssid>") entries += "$s|$b"
                else if (b.isNotBlank()) entries += "$s|$b"
            }
            if (entries.isEmpty()) {
                showToast(getString(R.string.calibration_auto_no_wifi))
                return
            }
            // 只有一条且扫描为空：多半是定位没开（Wi-Fi 扫描/SSID 都依赖定位权限与开关）
            if (scans.isEmpty() && entries.size <= 1) {
                showToast("只拿到当前连接：附近扫描需要定位已开启（Wi-Fi 扫描依赖定位权限）")
            }
            val list = entries.toList()
            ctx.wifiEntries = list
            val first = list.first().split("|", limit = 2)
            val v1 = first.getOrNull(0).orEmpty()
            val v2 = first.getOrNull(1).orEmpty()
            ctx.sharedPrefs.edit().putString("sim_wifi_v1", v1).putString("sim_wifi_v2", v2).apply()
            sendSim("wifi", v1, v2, list)
            showToast(getString(R.string.calibration_wifi_collected, list.size))
        }.onFailure { showToast(it.message ?: "Wi-Fi") }
    }

    private fun sendSim(kind: String, v1: String, v2: String, list: List<String>? = null) {
        val lm = mockServiceViewModel.locationManager ?: return
        val enabled = requireContext().sharedPrefs.getBoolean("sim_${kind}_enabled", false)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching { MockServiceHelper.setSimConfig(lm, kind, enabled, v1, v2, list) }
        }
    }

    // ---- 一键校准 ----

    private fun showCalibrationDialog() {
        val ctx = requireContext()
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }
        val checks = listOf(
            android.widget.CheckBox(ctx).apply { text = getString(R.string.calibration_group_sensor); isChecked = true },
            android.widget.CheckBox(ctx).apply { text = getString(R.string.calibration_group_location); isChecked = true },
            android.widget.CheckBox(ctx).apply { text = getString(R.string.calibration_group_baseband); isChecked = true },
        )
        checks.forEach { box.addView(it) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.calibration_run)
            .setView(box)
            .setPositiveButton(R.string.calibration_run) { _, _ ->
                val selected = checks.mapIndexedNotNull { i, c -> i.takeIf { c.isChecked } }.toSet()
                if (selected.isEmpty()) {
                    showToast(getString(R.string.calibration_nothing_selected))
                } else {
                    runCalibration(selected)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runCalibration(groups: Set<Int>) {
        val ctx = requireContext()
        // 前置：模拟会话在跑时不能校准。
        // 框架侧的注入一旦生效就会**压制真实事件**，那时采到的全是我们自己生成的假数据
        // （自我校准）；开关打开但会话没跑时真实事件原样透传，可以校准。
        val lm = mockServiceViewModel.locationManager
        val sessionRunning = FakeLoc.enable || (lm != null && runCatching {
            MockServiceHelper.isMockStart(lm)
        }.getOrDefault(false))
        if (sessionRunning) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("无法校准")
                .setMessage(
                    "模拟正在运行：框架侧此刻在压制真实传感器，采到的只会是我们自己注入的数据。\n\n" +
                        "请先到 Location Mock 页停止模拟，再回来校准。"
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val doSensor = GROUP_SENSOR in groups
        val doLocation = GROUP_LOCATION in groups
        val doBaseband = GROUP_BASEBAND in groups
        val token = ++runToken
        // 采集 dialog：**实时波形** + 静止自检读数（图上那条线就是判定用的量）
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_sensor_trace, null)
        val chart = dialogView.findViewById<SensorTraceChart>(R.id.trace_chart)
        val caption = dialogView.findViewById<TextView>(R.id.trace_caption)
        val progressText = dialogView.findViewById<TextView>(R.id.trace_progress)
        val trace = SensorNoiseCalibrator.Trace()
        progressText.text = progressText(0)

        val progress = MaterialAlertDialogBuilder(ctx)
            .setTitle("正在采集真实噪声")
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> runToken++ }
            .create()
        // 与其它"改设置/采集"的对话框一致：点空白不关闭（避免误触把采集废掉）
        progress.setCanceledOnTouchOutside(false)
        progress.show()

        val base = ctx.sensorNoise
        viewLifecycleOwner.lifecycleScope.launch {
            val locationDeferred = if (doLocation) async(Dispatchers.IO) {
                runCatching { LocationAccuracyCalibrator.collect(ctx) }
                    .getOrElse {
                        LocationAccuracyCalibrator.Result(
                            gpsAccuracyMeters = null, fusedAccuracyMeters = null,
                            fusedDelayMs = null, gpsIntervalMs = null,
                            satellites = null, maxCn0 = null, meanCn0 = null,
                            gpsSamples = 0, fusedSamples = 0, ok = false,
                            message = "GPS/fused 采集异常：${it.message}",
                        )
                    }
            } else null
            val result = if (doSensor) withContext(Dispatchers.IO) {
                runCatching {
                    SensorNoiseCalibrator.collect(
                        ctx, base,
                        onProgress = { ms ->
                            activity?.runOnUiThread {
                                if (token == runToken && progress.isShowing) {
                                    progressText.text = progressText(ms)
                                    renderTrace(chart, caption, trace)
                                }
                            }
                        },
                        trace = trace,
                    )
                }.getOrElse {
                    SensorNoiseCalibrator.Result(base, "", false, "采集异常：${it.message}")
                }
            } else SensorNoiseCalibrator.Result(base, "", true, "传感器采集已跳过")
            if (token != runToken) {
                runCatching { progress.dismiss() }
                showToast("已取消校准")
                return@launch
            }
            runCatching { progress.dismiss() }
            // 三组数据**互相独立**：传感器这一组失败（例如检测到移动）只影响它自己，
            // 后面的 GPS / 基带照常测量并写入 —— 不再早退把其它组一起废掉。
            if (doSensor) {
                if (result.ok) {
                    ctx.sensorNoise = result.values
                    ctx.sensorNoiseReport = result.report
                    renderValues(ctx.sensorNoise)
                    _binding?.calibStatus?.text = result.report
                    showToast(result.message)
                    pushConfig()
                } else {
                    _binding?.calibStatus?.text = result.message
                    showToast(result.message)
                }
            }
            if (doLocation) {
                // GNSS 状态要等回调（最多 3 秒），不能堵在主线程上
                withContext(Dispatchers.IO) { autoFillGps() }
                locationDeferred?.let { applyLocationAccuracy(ctx, it.await()) }
            }
            if (doBaseband) {
                // 扫描结果需要等待广播/轮询，放到 IO 线程，避免阻塞主线程
                withContext(Dispatchers.IO) {
                    autoFillCell()
                    autoFillWifi()
                }
            }
        }
    }

    /**
     * 把实时波形与"σ / 阈值"读数刷到 dialog 上。
     *
     * 读数口径与最终判定一致：加速度看**模长 σ**（判定用的就是它）；陀螺判定用的是**单轴 σ**
     * 而图上只有模长，所以这一项标注为参考值——不把不同的量写成同一个名字。
     */
    private fun renderTrace(
        chart: SensorTraceChart,
        caption: TextView,
        trace: SensorNoiseCalibrator.Trace,
    ) {
        val accel = trace.accel()
        val gyro = trace.gyro()
        // 横轴固定为采集窗口（8s）：曲线从左侧向右生长，形状不随样本数被拉伸
        chart.submit(
            SensorTraceChart.Series(trace.accelTime(), accel),
            SensorTraceChart.Series(trace.gyroTime(), gyro),
            SensorNoiseCalibrator.DURATION_MS.toFloat(),
        )
        // 量程（min~max）与 σ 一起放在图下：图内只有线条（高度 75dp，图内文字会压住曲线）
        // 注意括号：不括起来的话 `.format` 只作用于后一段字符串（第一段的占位符会原样显示）
        caption.text = ("加速度模长 σ=%.4f 范围 %.4f~%.4f（静止阈值 %.2f）\n" +
            "陀螺模长 σ=%.4f 范围 %.4f~%.4f（判定用单轴 σ≤%.2f，此处为参考）").format(
            sigmaOf(accel), rangeOf(accel).first, rangeOf(accel).second,
            SensorNoiseCalibrator.MAX_ACCEL_NORM_SIGMA,
            sigmaOf(gyro), rangeOf(gyro).first, rangeOf(gyro).second,
            SensorNoiseCalibrator.MAX_GYRO_SIGMA,
        )
    }

    /** 模长序列的区间（min, max；空序列给 0） */
    private fun rangeOf(data: FloatArray): Pair<Float, Float> =
        Pair(data.minOrNull() ?: 0f, data.maxOrNull() ?: 0f)

    /** 模长序列的标准差（n<2 时给 0） */
    private fun sigmaOf(data: FloatArray): Double {
        if (data.size < 2) return 0.0
        val mean = data.sumOf { it.toDouble() } / data.size
        var acc = 0.0
        data.forEach { val d = it - mean; acc += d * d }
        return kotlin.math.sqrt(acc / data.size)
    }

    private fun progressText(elapsedMs: Long): String {
        val total = SensorNoiseCalibrator.DURATION_MS / 1000.0
        // 窗口在**第一个有效样本**到达时才开始 ⇒ 之前显示"等待中"。
        // 旧实现从注册时刻就报"已采集 x 秒"，传感器慢的时候看着进度在跑、其实一个样本都没到。
        if (elapsedMs <= 0L) {
            return "请把手机放在平稳的表面（桌面），保持静止 ${total.toInt()} 秒。\n\n" +
                "正在等待传感器数据…（收到第一个样本才开始计时）"
        }
        return "请把手机放在平稳的表面（桌面），保持静止 ${total.toInt()} 秒。\n\n" +
            "已采集 %.1f / %.1f 秒\n\n检测到移动会跳过本次传感器检测（GPS / 基带不受影响）。".format(elapsedMs / 1000.0, total)
    }

    // ---- 下发与回读 ----

    /** 把当前噪声档下发给系统侧（与设置页同一条 put_config 通道） */
    private fun pushConfig() {
        val lm = mockServiceViewModel.locationManager
        if (lm == null) {
            showToast("定位服务加载异常，噪声档未下发（已存本地）")
            return
        }
        val result = ConfigSync.push(requireContext(), lm)
        if (!result.isOk) {
            // 如实区分"没握手"和"系统侧拒绝"：两者都意味着**配置没生效**（本地已存）
            showToast("${result.message(requireContext())}（已存本地）")
            return
        }
        // 系统侧是**单向下发**（proxy 用 transact 无应答），紧接着的状态回读很可能跑在配置前面
        // ⇒ 实测会出现"刚保存完却回读到旧值"的假告警。延后一点再回读。
        binding.root.postDelayed({ _binding?.let { refreshEcho() } }, 400)
    }

    /**
     * 系统侧回读：`get_sensor_status` 里的 `noise_profile` 由原生层直接回报，
     * 与 App 侧的值对上才算真的生效。
     */
    private fun refreshEcho() {
        val lm = mockServiceViewModel.locationManager ?: run {
            binding.calibEcho.text = "系统侧回读：定位服务未就绪"
            return
        }
        val bundle = runCatching { MockServiceHelper.getSensorStatus(lm) }.getOrNull()
        if (bundle == null) {
            binding.calibEcho.text = "系统侧回读：模块未握手（LSPosed 未注入？）"
            return
        }
        if (!bundle.getBoolean("native_ready", false)) {
            binding.calibEcho.text = "系统侧回读：原生注入层未装载（「Binder 外周传感器模拟」关闭时正常）"
            return
        }
        val echo = bundle.getString("noise_profile") ?: ""
        if (echo.isBlank() || echo == "n/a") {
            // 注入层还是旧版本（LSPosed 在开机时装载 .so，换包后要重启才换得掉）
            binding.calibEcho.text = "系统侧回读：注入层未提供噪声档（旧版本 .so；重启手机后生效）"
            return
        }
        val local = requireContext().sensorNoise
        val digits = 4
        val matched = runCatching {
            val remote = echo.substringAfter("gy=").substringBefore(" bias=").split('/')
                .map { it.toFloat() }
            remote.size == 3 && (0 until 3).all { abs(remote[it] - local[SensorNoise.GYRO + it]) < 1e-4 }
        }.getOrDefault(false)
        val verdict = if (matched) "✅ 与原生的陀螺 σ 一致" else "⚠️ 与本地值不一致（可能还没下发成功）"
        binding.calibEcho.text = "系统侧回读：$echo\n$verdict"
    }

    // ---- 杂项 ----

    private fun showToast(message: String) {
        val ctx = activity ?: return
        ctx.runOnUiThread { Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show() }
    }

    @SuppressLint("MissingInflatedId")
    private fun showInputDialog(titleText: String, valueText: String, handler: (String) -> Unit) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_input, null)
        dialogView.findViewById<TextView>(R.id.title).text = titleText
        val value = dialogView.findViewById<TextInputEditText>(R.id.value)
        value.setText(valueText)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(null)
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ -> handler(value.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
        dialog.shiftAboveIme(requireActivity().window.decorView)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 页面销毁即作废在飞的采集：回写会碰到已释放的 binding
        runToken++
        rowValues.clear()
        fluctuationValues.clear()
        gpsAccuracyValue = null
        fusedAccuracyValue = null
        fusedDelayValue = null
        gpsIntervalValue = null
        satellitesValue = null
        maxCn0Value = null
        meanCn0Value = null
        _binding = null
    }
}
