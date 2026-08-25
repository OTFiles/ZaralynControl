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
import com.readboy.control.network.LoginStore
import com.readboy.control.network.ParentApiClient
import com.readboy.control.network.VersionDetector
import com.readboy.control.service.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {

    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

        // 登录 UI
        val etLoginMobile = view.findViewById<TextInputEditText>(R.id.etLoginMobile)
        val etLoginPassword = view.findViewById<TextInputEditText>(R.id.etLoginPassword)
        val btnLogin = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnLogin)
        val btnLogout = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnLogout)
        val tvLoginStatus = view.findViewById<TextView>(R.id.tvLoginStatus)

        // 手动 Token UI
        val etTokenUid = view.findViewById<TextInputEditText>(R.id.etTokenUid)
        val etTokenValue = view.findViewById<TextInputEditText>(R.id.etTokenValue)
        val btnSaveToken = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveToken)
        val tvTokenStatus = view.findViewById<TextView>(R.id.tvTokenStatus)

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

        // 登录状态显示
        updateLoginUi(etLoginMobile, etLoginPassword, btnLogin, btnLogout, tvLoginStatus)

        // 登录
        btnLogin.setOnClickListener {
            val mobile = etLoginMobile.text?.toString()?.trim() ?: ""
            val password = etLoginPassword.text?.toString() ?: ""
            if (mobile.isEmpty()) {
                Toast.makeText(requireContext(), "请输入家长手机号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                Toast.makeText(requireContext(), "请输入家长密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnLogin.isEnabled = false
            btnLogin.text = "登录中..."
            scope.launch {
                val result = ParentApiClient.login(requireContext(), mobile, password)
                if (result.success) {
                    LoginStore.saveLogin(
                        requireContext(),
                        result.uid,
                        result.token,
                        result.expireAt,
                        mobile
                    )
                    Toast.makeText(requireContext(), "登录成功 (uid=${result.uid})", Toast.LENGTH_LONG).show()
                    AppLogger.i("Settings", "家长账号登录成功: uid=${result.uid}")
                    etLoginPassword.text?.clear()
                    tvTokenStatus.text = ""
                } else {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                    AppLogger.e("Settings", "登录失败: ${result.message}")
                }
                updateLoginUi(etLoginMobile, etLoginPassword, btnLogin, btnLogout, tvLoginStatus)
                btnLogin.isEnabled = true
                btnLogin.text = "登录"
            }
        }

        // 退出登录
        btnLogout.setOnClickListener {
            LoginStore.clear(requireContext())
            Toast.makeText(requireContext(), "已退出登录", Toast.LENGTH_SHORT).show()
            AppLogger.i("Settings", "退出家长账号登录")
            updateLoginUi(etLoginMobile, etLoginPassword, btnLogin, btnLogout, tvLoginStatus)
            tvTokenStatus.text = ""
        }

        // 保存手动填写的 Token（uid + access_token）——保存时自动验证，验证失败回滚
        btnSaveToken.setOnClickListener {
            val uidStr = etTokenUid.text?.toString()?.trim() ?: ""
            val token = etTokenValue.text?.toString()?.trim() ?: ""
            if (uidStr.isEmpty() || token.isEmpty()) {
                Toast.makeText(requireContext(), "请填写 uid 和 access_token", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val uid = uidStr.toLongOrNull()
            if (uid == null || uid <= 0) {
                Toast.makeText(requireContext(), "uid 格式不正确（应为数字）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 记住原登录状态，验证失败时回滚
            val oldUid = LoginStore.getUid(requireContext())
            val oldToken = LoginStore.getToken(requireContext())
            val oldExpire = LoginStore.getExpireAt(requireContext())
            val oldMobile = LoginStore.getMobile(requireContext())

            btnSaveToken.isEnabled = false
            btnSaveToken.text = "验证中..."
            scope.launch {
                // 临时保存以便 baseParams 取 token 验证
                LoginStore.saveLogin(requireContext(), uid, token, 0L, "")
                val result = ParentApiClient.getDeviceList(requireContext())
                if (result.success) {
                    val deviceCount = result.response?.data?.size ?: 0
                    // 验证通过，正式保存（保留输入手机号为空）
                    LoginStore.saveLogin(requireContext(), uid, token, 0L, "")
                    tvTokenStatus.text = "Token 验证通过，绑定设备 $deviceCount 台"
                    Toast.makeText(requireContext(), "Token 验证通过，已保存", Toast.LENGTH_LONG).show()
                    AppLogger.i("Settings", "手动 Token 验证成功: uid=$uid, 设备数=$deviceCount")
                    etTokenValue.text?.clear()
                } else {
                    tvTokenStatus.text = "Token 无效: ${result.message}"
                    Toast.makeText(requireContext(), "Token 无效: ${result.message}", Toast.LENGTH_LONG).show()
                    AppLogger.e("Settings", "手动 Token 验证失败: ${result.message}")
                    // 回滚原登录状态
                    if (oldUid > 0 && oldToken.isNotEmpty()) {
                        LoginStore.saveLogin(requireContext(), oldUid, oldToken, oldExpire, oldMobile)
                    } else {
                        LoginStore.clear(requireContext())
                    }
                }
                updateLoginUi(etLoginMobile, etLoginPassword, btnLogin, btnLogout, tvLoginStatus)
                btnSaveToken.isEnabled = true
                btnSaveToken.text = "保存并验证 Token"
            }
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

    /** 更新登录 UI 状态 */
    private fun updateLoginUi(
        etMobile: TextInputEditText,
        etPassword: TextInputEditText,
        btnLogin: com.google.android.material.button.MaterialButton,
        btnLogout: com.google.android.material.button.MaterialButton,
        tvStatus: TextView
    ) {
        val loggedIn = LoginStore.isLoggedIn(requireContext())
        if (loggedIn) {
            val mobile = LoginStore.getMobile(requireContext())
            val uid = LoginStore.getUid(requireContext())
            val expireAt = LoginStore.getExpireAt(requireContext())
            val expireText = if (expireAt > 0) {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                "，有效期至 " + fmt.format(Date(expireAt * 1000))
            } else ""
            tvStatus.text = "已登录：$mobile (uid=$uid)$expireText"
            etMobile.isEnabled = false
            etPassword.isEnabled = false
            btnLogin.isEnabled = false
            btnLogout.isEnabled = true
        } else {
            tvStatus.text = "未登录（时间管控与允许输入密码需登录）"
            etMobile.isEnabled = true
            etPassword.isEnabled = true
            btnLogin.isEnabled = true
            btnLogout.isEnabled = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}