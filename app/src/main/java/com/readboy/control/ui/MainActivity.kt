package com.readboy.control.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayoutMediator
import com.readboy.control.R
import com.readboy.control.databinding.ActivityMainBinding
import com.readboy.control.AppLogger
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

        // 异步检测版本（日志）
        Thread {
            val version = VersionDetector.detect(this)
            AppLogger.i("MainActivity", "检测到版本: $version")
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}