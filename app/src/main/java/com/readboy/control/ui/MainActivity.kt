package com.readboy.control.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayoutMediator
import com.readboy.control.R
import com.readboy.control.databinding.ActivityMainBinding
import com.readboy.control.AppLogger
import com.readboy.control.network.DeviceUtil
import com.readboy.control.network.VersionDetector

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val tabTitles = listOf(
        "管控列表",
        "密码管理",
        "时间管控",
        "同步状态",
        "设置"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置 Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        // 设置 ViewPager
        val pagerAdapter = MainPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter

        // 绑定 TabLayout
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles.getOrElse(position) { "Tab $position" }
        }.attach()

        // 检测版本 + 远程模式弹窗（自定义 MD3 弹窗）
        Thread {
            val version = VersionDetector.detect(this)
            AppLogger.i("MainActivity", "检测到版本: $version")

            if (version == VersionDetector.PmsVersion.UNKNOWN && !DeviceUtil.hasRemoteSerial()) {
                runOnUiThread { showRemoteModeDialog() }
            }
        }.start()
    }

    private fun showRemoteModeDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_remote_mode, null)
        val btnLater = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialogBtnLater)
        val btnEnter = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialogBtnEnter)
        val serialInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.dialogSerialInput)

        // 预填之前保存的序列号
        serialInput.setText(DeviceUtil.getRemoteSerial() ?: "")

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        // 透明背景，只显示自定义卡片
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))

        btnLater.setOnClickListener {
            dialog.dismiss()
            AppLogger.w("MainActivity", "用户取消远程模式设置，可在设置页手动输入序列号")
            Toast.makeText(this, "可在设置页输入序列号启用远程模式", Toast.LENGTH_LONG).show()
        }

        btnEnter.setOnClickListener {
            val serial = serialInput.text?.toString()?.trim() ?: ""
            if (serial.isEmpty()) {
                Toast.makeText(this, "序列号不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            DeviceUtil.saveRemoteSerial(serial)
            AppLogger.i("MainActivity", "远程模式已启用，设备序列号: $serial")
            Toast.makeText(this, "远程模式已启用，序列号: $serial", Toast.LENGTH_LONG).show()
            dialog.dismiss()
            // 刷新 UI 重新加载 fragment
            recreate()
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}