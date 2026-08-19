package com.readboy.control.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.readboy.control.AppLogger
import com.readboy.control.R
import com.readboy.control.db.MirrorDatabase
import com.readboy.control.db.MirrorUserInfo
import com.readboy.control.network.CloudSyncEngine
import com.readboy.control.network.SyncEngine
import com.readboy.control.network.VersionDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PasswordFragment : Fragment() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_password, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etPassword = view.findViewById<TextInputEditText>(R.id.etPassword)
        val btnChangePassword = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnChangePassword)
        val switchAllowInputPwd = view.findViewById<MaterialSwitch>(R.id.switchAllowInputPwd)
        val btnPullCloud = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPullCloud)
        val btnPushCloud = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPushCloud)

        // 加载当前密码到输入框
        scope.launch {
            val db = MirrorDatabase.getInstance(requireContext())
            val info = db.userInfoDao().get()
            if (info != null) {
                etPassword.setText(info.password)
                switchAllowInputPwd.isChecked = info.is_allow_input_pwd == 1
            }
        }

        // 修改密码
        btnChangePassword.setOnClickListener {
            val newPwd = etPassword.text?.toString() ?: ""
            scope.launch {
                val db = MirrorDatabase.getInstance(requireContext())
                val info = MirrorUserInfo(
                    password = newPwd,
                    is_long_pwd = if (newPwd.length > 6) 1 else 0,
                    is_allow_input_pwd = if (switchAllowInputPwd.isChecked) 1 else 0,
                    sync_status = 2
                )
                db.userInfoDao().clear()
                db.userInfoDao().insert(info)
                AppLogger.i("PasswordFragment", "密码已修改: ${if (newPwd.isEmpty()) "清除" else "设为 ${"*".repeat(newPwd.length)}"}")
                Toast.makeText(requireContext(), "密码已保存到镜像库，点击同步生效", Toast.LENGTH_LONG).show()
            }
        }

        // 允许输入密码
        switchAllowInputPwd.setOnCheckedChangeListener { _, isChecked ->
            scope.launch {
                val db = MirrorDatabase.getInstance(requireContext())
                val info = db.userInfoDao().get()
                if (info != null) {
                    db.userInfoDao().update(info.copy(is_allow_input_pwd = if (isChecked) 1 else 0, sync_status = 2))
                } else {
                    db.userInfoDao().insert(
                        MirrorUserInfo(is_allow_input_pwd = if (isChecked) 1 else 0, sync_status = 2)
                    )
                }
                AppLogger.i("PasswordFragment", "允许输入密码: $isChecked")
            }
        }

        // 云端拉取
        btnPullCloud.setOnClickListener {
            scope.launch {
                val imei = getDeviceSerial()
                if (imei == null) {
                    Toast.makeText(requireContext(), "无法获取设备序列号，请授予 READ_PHONE_STATE 权限", Toast.LENGTH_LONG).show()
                    return@launch
                }
                Toast.makeText(requireContext(), "正在拉取云端配置...", Toast.LENGTH_SHORT).show()
                val result = CloudSyncEngine.pullFromCloud(imei)
                if (result.success && result.responseBody != null) {
                    CloudSyncEngine.parseAndUpdateMirror(requireContext(), result.responseBody)
                    Toast.makeText(requireContext(), "云端配置已拉取", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        // 云端上传
        btnPushCloud.setOnClickListener {
            scope.launch {
                val imei = getDeviceSerial()
                if (imei == null) {
                    Toast.makeText(requireContext(), "无法获取设备序列号，请授予 READ_PHONE_STATE 权限", Toast.LENGTH_LONG).show()
                    return@launch
                }
                Toast.makeText(requireContext(), "正在上传到云端...", Toast.LENGTH_SHORT).show()
                val result = CloudSyncEngine.pushToCloud(imei)
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getDeviceSerial(): String? {
        return try {
            android.os.Build.getSerial()
        } catch (e: SecurityException) {
            try {
                val proc = Runtime.getRuntime().exec("getprop ro.serialno")
                val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
                val serial = reader.readLine()?.trim()
                reader.close()
                proc.destroy()
                serial
            } catch (e2: Exception) {
                AppLogger.e("PasswordFragment", "获取序列号失败: ${e2.message}", e2)
                null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}