package com.readboy.control.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.readboy.control.AppLogger
import com.readboy.control.R
import com.readboy.control.network.DeviceUtil
import com.readboy.control.network.LoginStore
import com.readboy.control.network.ParentApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 时间管控（control_time）界面
 *
 * 需家长账号登录（api-super 域）：
 * - 拉取：GET parent_control/time_setting（sn + token + imei）
 * - 保存：POST parent_control/set_time（sn + token + imei + tid + group + period_status + periods + total_time）
 */
class TimeControlFragment : Fragment() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_time_control, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvLoginTip = view.findViewById<TextView>(R.id.tvTimeCtrlLoginTip)
        val tvInfo = view.findViewById<TextView>(R.id.tvTimeCtrlInfo)
        val btnRefresh = view.findViewById<MaterialButton>(R.id.btnRefreshTimeCtrl)
        val cardEdit = view.findViewById<MaterialCardView>(R.id.cardTimeCtrlEdit)
        val etTotal = view.findViewById<TextInputEditText>(R.id.etTimeTotal)
        val etStart = view.findViewById<TextInputEditText>(R.id.etTimeStart)
        val etEnd = view.findViewById<TextInputEditText>(R.id.etTimeEnd)
        val etGroup = view.findViewById<TextInputEditText>(R.id.etTimeGroup)
        val switchPeriod = view.findViewById<MaterialSwitch>(R.id.switchPeriodStatus)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveTimeCtrl)

        // 未登录：编辑区禁用变灰
        val loggedIn = LoginStore.isLoggedIn(requireContext())
        cardEdit.isEnabled = loggedIn
        btnRefresh.isEnabled = loggedIn
        val editViews = listOf(etTotal, etStart, etEnd, etGroup, switchPeriod, btnSave)
        if (!loggedIn) {
            cardEdit.alpha = 0.4f
            btnRefresh.alpha = 0.4f
            editViews.forEach { it.isEnabled = false }
            tvLoginTip.text = "修改时间管控需要先登录家长账号（设置页）。未登录仅可查看上方说明。"
        } else {
            cardEdit.alpha = 1.0f
            btnRefresh.alpha = 1.0f
            editViews.forEach { it.isEnabled = true }
            tvLoginTip.text = "已登录，可拉取与修改时间管控"
        }

        // 拉取当前配置
        btnRefresh.setOnClickListener {
            if (!LoginStore.isLoggedIn(requireContext())) {
                Toast.makeText(requireContext(), "请先在设置中登录家长账号", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val imei = DeviceUtil.getEffectiveSerial()
            if (imei.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "无法获取设备序列号，请在设置中填写", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            btnRefresh.isEnabled = false
            tvInfo.text = "拉取中..."
            scope.launch {
                val result = ParentApiClient.getTimeSetting(requireContext(), imei)
                if (result.success && result.response != null) {
                    renderConfig(result.response, tvInfo)
                    // 预填编辑表单
                    val data = result.response.data?.firstOrNull()
                    if (data != null) {
                        data.total_time?.let { etTotal.setText((it / 60).toString()) }
                        data.periods?.firstOrNull()?.let { p ->
                            etStart.setText(secToHm(p.start ?: 0))
                            etEnd.setText(secToHm(p.end ?: 0))
                        }
                        data.group?.let { etGroup.setText(it) }
                        switchPeriod.isChecked = (data.period_status ?: 0) == 1
                    }
                    AppLogger.i("TimeControl", "拉取时间管控成功")
                } else {
                    tvInfo.text = "拉取失败: ${result.message}"
                    AppLogger.e("TimeControl", "拉取失败: ${result.message}")
                }
                btnRefresh.isEnabled = true
            }
        }

        // 保存到云端
        btnSave.setOnClickListener {
            if (!LoginStore.isLoggedIn(requireContext())) {
                Toast.makeText(requireContext(), "请先在设置中登录家长账号", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val imei = DeviceUtil.getEffectiveSerial()
            if (imei.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "无法获取设备序列号，请在设置中填写", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val totalMinutes = etTotal.text?.toString()?.toIntOrNull() ?: 0
            val startHm = etStart.text?.toString()?.trim() ?: ""
            val endHm = etEnd.text?.toString()?.trim() ?: ""
            val group = etGroup.text?.toString()?.trim() ?: ""
            val periodStatus = if (switchPeriod.isChecked) 1 else 0

            if (group.isEmpty()) {
                Toast.makeText(requireContext(), "请填写管控星期", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // periods JSON：[{"start":秒,"end":秒}]
            var periodsJson: String? = null
            if (periodStatus == 1 && startHm.isNotEmpty() && endHm.isNotEmpty()) {
                val startSec = hmToSec(startHm)
                if (startSec == null) {
                    Toast.makeText(requireContext(), "开始时间格式错误，应为 HH:mm", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val endSec = hmToSec(endHm)
                if (endSec == null) {
                    Toast.makeText(requireContext(), "结束时间格式错误，应为 HH:mm", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                periodsJson = "[{\"start\":$startSec,\"end\":$endSec}]"
            }

            btnSave.isEnabled = false
            btnSave.text = "保存中..."
            scope.launch {
                val result = ParentApiClient.setTime(
                    requireContext(),
                    imei,
                    tid = null,
                    group = group,
                    periodStatus = periodStatus,
                    periodsJson = periodsJson,
                    totalTime = if (totalMinutes > 0) totalMinutes * 60 else null
                )
                if (result.success) {
                    Toast.makeText(requireContext(), "时间管控已保存到云端", Toast.LENGTH_LONG).show()
                    AppLogger.i("TimeControl", "时间管控已保存: group=$group total=${totalMinutes}min status=$periodStatus")
                    // 保存后刷新展示
                    val r = ParentApiClient.getTimeSetting(requireContext(), imei)
                    if (r.success && r.response != null) renderConfig(r.response, tvInfo)
                } else {
                    Toast.makeText(requireContext(), "保存失败: ${result.message}", Toast.LENGTH_LONG).show()
                    AppLogger.e("TimeControl", "保存失败: ${result.message}")
                }
                btnSave.isEnabled = true
                btnSave.text = "保存到云端"
            }
        }
    }

    /** 展示当前配置 */
    private fun renderConfig(
        resp: ParentApiClient.TimeSettingResponse,
        tvInfo: TextView
    ) {
        val sb = StringBuilder()
        val data = resp.data?.firstOrNull()
        if (data != null) {
            sb.append("管控时长: ").append(data.total_time?.let { "${it / 60} 分钟/天" } ?: "不限").append("\n")
            sb.append("星期: ").append(data.group ?: "-").append("\n")
            sb.append("时间段: ").append(data.period_status?.let { if (it == 1) "启用" else "禁用" } ?: "-")
            data.periods?.firstOrNull()?.let { p ->
                sb.append("  [").append(secToHm(p.start ?: 0)).append(" - ").append(secToHm(p.end ?: 0)).append("]")
            }
            sb.append("\n")
            data.tid?.let { sb.append("tid: ").append(it).append("\n") }
        } else {
            sb.append("暂无时间管控配置（可新建）\n")
        }
        resp.anti_addiction?.let { aa ->
            sb.append("防沉迷: 使用 ")
                .append(aa.use_duration?.let { "${it / 60} 分钟" } ?: "-")
                .append(" 休息 ")
                .append(aa.rest_duration?.let { "${it / 60} 分钟" } ?: "-")
        }
        resp.time_switch?.let { sb.append("\n时间管控开关: ").append(if (it == 1) "开" else "关") }
        tvInfo.text = sb.toString()
    }

    /** HH:mm → 秒（自当天 0 点起算） */
    private fun hmToSec(hm: String): Int? {
        val parts = hm.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 3600 + m * 60
    }

    /** 秒 → HH:mm */
    private fun secToHm(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return String.format("%02d:%02d", h, m)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
