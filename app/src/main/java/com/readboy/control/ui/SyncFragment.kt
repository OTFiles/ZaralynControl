package com.readboy.control.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.readboy.control.AppLogger
import com.readboy.control.R
import com.readboy.control.network.CloudSyncEngine
import com.readboy.control.network.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SyncFragment : Fragment(), AppLogger.OnLogListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tvLog: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_sync, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvLog = view.findViewById(R.id.tvLog)
        tvLog?.text = AppLogger.getLogText()
        AppLogger.addListener(this)

        // 立即同步
        view.findViewById<View>(R.id.btnSyncNow).setOnClickListener {
            scope.launch {
                runInIO {
                    SyncEngine.sync(requireContext())
                }
            }
        }

        // 复制日志
        view.findViewById<View>(R.id.btnCopyLog).setOnClickListener {
            val text = AppLogger.getLogText()
            if (text.isNotEmpty()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.text = text
                Toast.makeText(requireContext(), "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }

        // 清空日志
        view.findViewById<View>(R.id.btnClearLog).setOnClickListener {
            AppLogger.clear()
            tvLog?.text = ""
        }
    }

    override fun onLogAdded(line: String) {
        tvLog?.post {
            val current = tvLog?.text?.toString() ?: ""
            tvLog?.text = if (current.length > 30000) {
                current.takeLast(25000) + "\n" + line
            } else {
                current + "\n" + line
            }
        }
    }

    private fun runInIO(block: suspend () -> Any?) {
        scope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: Exception) {
                    AppLogger.e("SyncFragment", "同步异常: ${e.message}", e)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        AppLogger.removeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}