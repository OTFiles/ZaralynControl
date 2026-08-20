package com.readboy.control.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.readboy.control.AppLogger
import com.readboy.control.R
import com.readboy.control.network.VersionDetector
import com.readboy.control.service.SyncWorker

class SettingsFragment : Fragment() {

    private lateinit var prefs: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = requireContext().getSharedPreferences("zaralyn_control_prefs", Context.MODE_PRIVATE)

        val switchAutoSync = view.findViewById<MaterialSwitch>(R.id.switchAutoSync)
        val switchCloudSync = view.findViewById<MaterialSwitch>(R.id.switchCloudSync)
        val seekInterval = view.findViewById<SeekBar>(R.id.seekInterval)
        val tvIntervalValue = view.findViewById<TextView>(R.id.tvIntervalValue)
        val tvVersionInfo = view.findViewById<TextView>(R.id.tvVersionInfo)

        // 自动同步
        val autoSync = prefs.getBoolean("auto_sync_enabled", true)
        switchAutoSync.isChecked = autoSync

        // 云端同步
        val cloudSync = prefs.getBoolean("cloud_sync_enabled", false)
        switchCloudSync.isChecked = cloudSync

        // 扫描间隔（1~60 分钟）
        val interval = prefs.getLong("scan_interval_minutes", 1)
        seekInterval.progress = interval.toInt() - 1
        tvIntervalValue.text = "$interval 分钟"

        // 版本信息
        val version = VersionDetector.detect(requireContext())
        tvVersionInfo.text = when (version) {
            VersionDetector.PmsVersion.NEW -> "新版本 (install_app_list)"
            VersionDetector.PmsVersion.OLD -> "老版本 (forbidden_app)"
            VersionDetector.PmsVersion.UNKNOWN -> "未检测到家长管理"
        }

        // 监听器
        switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_sync_enabled", isChecked).apply()
            if (isChecked) {
                val interval = seekInterval.progress + 1
                SyncWorker.schedule(requireContext(), interval.toLong())
            } else {
                SyncWorker.cancel(requireContext())
            }
        }

        switchCloudSync.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("cloud_sync_enabled", isChecked).apply()
        }

        seekInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = progress + 1
                tvIntervalValue.text = "$minutes 分钟"
                if (fromUser) {
                    prefs.edit().putLong("scan_interval_minutes", minutes.toLong()).apply()
                    if (switchAutoSync.isChecked) {
                        SyncWorker.schedule(requireContext(), minutes.toLong())
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 自定义远程设备序列号（输入框 + 保存按钮）
        val etRemoteSerial = view.findViewById<TextInputEditText>(R.id.etRemoteSerial)
        val btnSaveRemoteSerial = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveRemoteSerial)

        // 加载已保存的远程序列号
        val savedSerial = prefs.getString("remote_serial", "") ?: ""
        etRemoteSerial.setText(savedSerial)

        btnSaveRemoteSerial.setOnClickListener {
            val serial = etRemoteSerial.text?.toString()?.trim() ?: ""
            prefs.edit().putString("remote_serial", serial).apply()
            if (serial.isNotEmpty()) {
                Toast.makeText(requireContext(), "已设置远程序列号: $serial，仅发送请求不修改本机数据库", Toast.LENGTH_LONG).show()
                AppLogger.i("Settings", "设置远程设备序列号: $serial（仅请求模式）")
            } else {
                Toast.makeText(requireContext(), "已清除远程序列号，使用本机设备", Toast.LENGTH_LONG).show()
                AppLogger.i("Settings", "清除远程序列号，恢复本机模式")
            }
        }

        // 启动时确保计划同步
        if (autoSync) {
            SyncWorker.schedule(requireContext(), interval)
        }
    }
}