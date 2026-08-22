package com.readboy.control.ui

import android.os.Bundle
import android.widget.EditText
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

        // 检测版本 + 远程模式弹窗
        Thread {
            val version = VersionDetector.detect(this)
            AppLogger.i("MainActivity", "检测到版本: $version")

            if (version == VersionDetector.PmsVersion.UNKNOWN && !DeviceUtil.hasRemoteSerial()) {
                runOnUiThread { showRemoteModeDialog() }
            }
        }.start()
    }

    private fun showRemoteModeDialog() {
        val input = EditText(this).apply {
            hint = "设备序列号"
            setText(DeviceUtil.getRemoteSerial() ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("未检测到家长管理 App")
            .setMessage("本机未安装家长管理，将进入远程管控模式。\n\n请输入目标设备的序列号（imei），所有操作将通过 API 直接发送到云端，不修改本机数据库。")
            .setView(input, 40, 20, 40, 20)
            .setPositiveButton("进入远程模式") { _, _ ->
                val serial = input.text?.toString()?.trim() ?: ""
                if (serial.isNotEmpty()) {
                    DeviceUtil.saveRemoteSerial(serial)
                    AppLogger.i("MainActivity", "远程模式已启用，设备序列号: $serial")
                    Toast.makeText(this, "远程模式已启用，序列号: $serial", Toast.LENGTH_LONG).show()
                    // 刷新 UI 重新加载 fragment
                    recreate()
                } else {
                    Toast.makeText(this, "序列号不能为空", Toast.LENGTH_SHORT).show()
                    showRemoteModeDialog()
                }
            }
            .setNegativeButton("稍后设置") { _, _ ->
                AppLogger.w("MainActivity", "用户取消远程模式设置，可在设置页手动输入序列号")
                Toast.makeText(this, "可在设置页输入序列号启用远程模式", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}