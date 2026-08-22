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
import com.readboy.control.network.DeviceUtil
import com.readboy.control.network.SignUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

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

        // 远程模式：禁用本地密码修改，改用云端直达
        if (DeviceUtil.isRemoteMode()) {
            btnChangePassword.text = "上传到云端"
            switchAllowInputPwd.isEnabled = false
            etPassword.hint = "输入新密码，点击上传到云端"
        }

        // 修改密码 / 上传云端
        btnChangePassword.setOnClickListener {
            val newPwd = etPassword.text?.toString() ?: ""
            if (DeviceUtil.isRemoteMode()) {
                uploadPasswordToCloud(newPwd)
            } else {
                savePasswordToMirror(newPwd, switchAllowInputPwd.isChecked)
            }
        }

        // 允许输入密码（仅本地模式可用）
        switchAllowInputPwd.setOnCheckedChangeListener { _, isChecked ->
            if (!DeviceUtil.isRemoteMode()) {
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
        }

        // 云端拉取
        btnPullCloud.setOnClickListener {
            scope.launch {
                val imei = DeviceUtil.getEffectiveSerial()
                if (imei.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), "无法获取设备序列号，请在设置中填写", Toast.LENGTH_LONG).show()
                    return@launch
                }
                Toast.makeText(requireContext(), "正在拉取云端配置 (imei=$imei)...", Toast.LENGTH_SHORT).show()
                val result = CloudSyncEngine.pullFromCloud(imei)
                if (result.success && result.responseBody != null) {
                    // 远程模式也写入镜像库（用户明确要求本地数据库通过云端拉取建立）
                    CloudSyncEngine.parseAndUpdateMirror(requireContext(), result.responseBody)
                    Toast.makeText(requireContext(), "云端配置已拉取并更新镜像库", Toast.LENGTH_LONG).show()
                    // 刷新 UI
                    loadPasswordFromDb(etPassword, switchAllowInputPwd)
                    AppLogger.i("PasswordFragment", "云端拉取成功")
                } else {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        // 云端上传
        btnPushCloud.setOnClickListener {
            scope.launch {
                val imei = DeviceUtil.getEffectiveSerial()
                if (imei.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), "无法获取设备序列号，请在设置中填写", Toast.LENGTH_LONG).show()
                    return@launch
                }
                Toast.makeText(requireContext(), "正在上传到云端 (imei=$imei)...", Toast.LENGTH_SHORT).show()
                val result = CloudSyncEngine.pushToCloud(imei)
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadPasswordFromDb(
        etPassword: TextInputEditText,
        switchAllowInputPwd: MaterialSwitch
    ) {
        scope.launch {
            val db = MirrorDatabase.getInstance(requireContext())
            val info = db.userInfoDao().get()
            if (info != null) {
                etPassword.setText(info.password)
                switchAllowInputPwd.isChecked = info.is_allow_input_pwd == 1
                AppLogger.i("PasswordFragment", "UI已刷新: password=${if (info.password.isNotEmpty()) "已设置" else "未设置"}, allow_input_pwd=${info.is_allow_input_pwd}")
            } else {
                etPassword.setText("")
                switchAllowInputPwd.isChecked = true
                AppLogger.i("PasswordFragment", "镜像库无密码记录，UI已清空")
            }
        }
    }

    private fun savePasswordToMirror(newPwd: String, allowInputPwd: Boolean) {
        scope.launch {
            val db = MirrorDatabase.getInstance(requireContext())
            val info = MirrorUserInfo(
                password = newPwd,
                is_long_pwd = if (newPwd.length > 6) 1 else 0,
                is_allow_input_pwd = if (allowInputPwd) 1 else 0,
                sync_status = 2
            )
            db.userInfoDao().clear()
            db.userInfoDao().insert(info)
            AppLogger.i("PasswordFragment", "密码已修改: ${if (newPwd.isEmpty()) "清除" else "已设置"}")
            Toast.makeText(requireContext(), "密码已保存到镜像库，点击同步生效", Toast.LENGTH_LONG).show()
        }
    }

    private fun uploadPasswordToCloud(newPwd: String) {
        scope.launch {
            val imei = DeviceUtil.getEffectiveSerial() ?: return@launch
            withContext(Dispatchers.IO) {
                try {
                    val p = SignUtil.getCommonParams(imei)
                    val body = StringBuilder()
                        .append("signature=").append(p["signature"])
                        .append("&imei=").append(imei)
                        .append("&timestamp=").append(p["timestamp"])
                        .append("&app_id=").append(p["app_id"])
                        .append("&password=").append(newPwd)
                        .append("&is_long_pwd=").append(if (newPwd.length > 6) 1 else 0)
                        .toString()

                    val url = URL("http://parent-manage.readboy.com/api/v1/password/upload")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 15000
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.outputStream.write(body.toByteArray(Charsets.UTF_8))

                    val code = conn.responseCode
                    val resp = if (code in 200..299) {
                        conn.inputStream.bufferedReader().readText()
                    } else {
                        conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                    }
                    conn.disconnect()

                    AppLogger.i("PasswordFragment", "远程密码上传: HTTP $code $resp")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "密码已上传到云端: HTTP $code", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    AppLogger.e("PasswordFragment", "远程密码上传失败: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "上传失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}