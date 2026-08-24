package com.readboy.control.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.readboy.control.AppLogger
import com.readboy.control.R
import com.readboy.control.ZaralynControlApp
import com.readboy.control.db.MirrorControlItem
import com.readboy.control.db.MirrorDatabase
import com.readboy.control.network.CloudSyncEngine
import com.readboy.control.network.DeviceUtil
import com.readboy.control.network.SyncEngine
import com.readboy.control.network.VersionDetector
import com.readboy.control.service.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ControlListFragment : Fragment() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var adapter: ControlListAdapter? = null
    private var items = mutableListOf<MirrorControlItem>()
    private var filteredItems = mutableListOf<MirrorControlItem>()
    private var searchQuery = ""
    private var statsCardView: com.google.android.material.card.MaterialCardView? = null
    private var tvStatTotalView: TextView? = null
    private var tvStatAllowedView: TextView? = null
    private var tvStatDisabledView: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_control_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val listView = view.findViewById<ListView>(R.id.listApps)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)
        val statsCard = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.statsCard)
        val tvStatTotal = view.findViewById<TextView>(R.id.tvStatTotal)
        val tvStatAllowed = view.findViewById<TextView>(R.id.tvStatAllowed)
        val tvStatDisabled = view.findViewById<TextView>(R.id.tvStatDisabled)
        val etSearch = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSearch)
        statsCardView = statsCard
        tvStatTotalView = tvStatTotal
        tvStatAllowedView = tvStatAllowed
        tvStatDisabledView = tvStatDisabled
        listView.emptyView = emptyView

        // 远程模式：更新按钮文本和流程提示
        val btnPull = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPullLocal)
        val btnPush = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPushLocal)
        val tvFlowHint = view.findViewById<TextView>(R.id.tvFlowHint)
        val isRemote = DeviceUtil.isRemoteMode()
        if (isRemote) {
            tvFlowHint.text = "远程模式：仅支持云端拉取配置（服务器只读）"
            btnPull.text = getString(R.string.btn_pull_remote)
            btnPush.isEnabled = false
            btnPush.alpha = 0.5f
            btnPush.text = "服务器只读"
        }

        adapter = ControlListAdapter(requireContext(), filteredItems)
        listView.adapter = adapter

        // 拉取/推送按钮
        btnPull.setOnClickListener {
            if (isRemote) {
                pullFromCloud()
            } else {
                pullFromProvider()
            }
        }
        btnPush.setOnClickListener {
            if (isRemote) {
                Toast.makeText(requireContext(), "服务器只读：不支持远程修改管控列表", Toast.LENGTH_LONG).show()
            } else {
                pushToProvider()
            }
        }

        // 添加应用按钮
        view.findViewById<View>(R.id.fabAddApp).setOnClickListener {
            showAddAppDialog()
        }

        // 搜索过滤
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                filterAndUpdate(statsCard, tvStatTotal, tvStatAllowed, tvStatDisabled)
            }
        })

        // 加载镜像库数据
        loadMirror(statsCard, tvStatTotal, tvStatAllowed, tvStatDisabled)
    }

    private fun filterAndUpdate(
        statsCard: com.google.android.material.card.MaterialCardView?,
        tvStatTotal: TextView?,
        tvStatAllowed: TextView?,
        tvStatDisabled: TextView?
    ) {
        filteredItems.clear()
        filteredItems.addAll(
            if (searchQuery.isEmpty()) items
            else items.filter {
                it.package_name.lowercase().contains(searchQuery) ||
                (it.app_name?.lowercase()?.contains(searchQuery) ?: false)
            }
        )
        adapter?.notifyDataSetChanged()

        // 更新统计
        if (items.isNotEmpty()) {
            statsCard?.visibility = View.VISIBLE
            val total = items.size
            val allowed = items.count { it.disabled_state == 0 }
            val disabled = total - allowed
            tvStatTotal?.text = "共 $total 项"
            tvStatAllowed?.text = "允许 $allowed"
            tvStatDisabled?.text = "禁用 $disabled"
        } else {
            statsCard?.visibility = View.GONE
        }
    }

    private fun loadMirror(
        statsCard: com.google.android.material.card.MaterialCardView? = null,
        tvStatTotal: TextView? = null,
        tvStatAllowed: TextView? = null,
        tvStatDisabled: TextView? = null
    ) {
        scope.launch {
            val db = MirrorDatabase.getInstance(requireContext())
            val list = db.controlListDao().getAll()
            items.clear()
            items.addAll(list)
            filterAndUpdate(statsCard, tvStatTotal, tvStatAllowed, tvStatDisabled)
            AppLogger.d("ControlListFragment", "镜像库加载 ${items.size} 项")
        }
    }

    private fun pullFromProvider() {
        AppLogger.i("ControlListFragment", "手动从家长管理拉取")
        Toast.makeText(requireContext(), "正在拉取...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = SyncEngine.pullFromProvider(requireContext())
            AppLogger.i("ControlListFragment", "拉取结果: ${result.message}")
            Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
            // 刷新镜像库（含统计）
            val db = MirrorDatabase.getInstance(requireContext())
            val list = db.controlListDao().getAll()
            items.clear()
            items.addAll(list)
            filterAndUpdate(statsCardView, tvStatTotalView, tvStatAllowedView, tvStatDisabledView)
            // 同步后更新后台计划
            SyncWorker.schedule(requireContext())
        }
    }

    private fun pushToProvider() {
        AppLogger.i("ControlListFragment", "手动覆盖家长管理")
        Toast.makeText(requireContext(), "正在覆盖...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = SyncEngine.pushToProvider(requireContext())
            AppLogger.i("ControlListFragment", "覆盖结果: ${result.message}")
            Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
        }
    }

    /** 远程模式：从云端拉取并更新镜像库 */
    private fun pullFromCloud() {
        AppLogger.i("ControlListFragment", "远程模式：从云端拉取配置")
        Toast.makeText(requireContext(), "正在从云端拉取...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val imei = DeviceUtil.getEffectiveSerial()
            if (imei.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "无法获取设备序列号，请在设置中填写", Toast.LENGTH_LONG).show()
                return@launch
            }
            AppLogger.i("ControlListFragment", "1/3 发送请求 imei=$imei")
            val result = CloudSyncEngine.pullFromCloud(imei)
            AppLogger.i("ControlListFragment", "2/3 请求完成 success=${result.success}, bodyLen=${result.responseBody?.length ?: 0}")
            if (result.success && result.responseBody != null) {
                // 云端拉取结果写入镜像库（远程模式同样写入，用云端数据建立本地数据库）
                CloudSyncEngine.parseAndUpdateMirror(requireContext(), result.responseBody)
                AppLogger.i("ControlListFragment", "3/3 镜像库已更新，刷新列表")
                Toast.makeText(requireContext(), "已从远程拉取 ${result.responseBody.length} bytes", Toast.LENGTH_LONG).show()
                reloadFromMirror()
            } else {
                AppLogger.w("ControlListFragment", "拉取失败: ${result.message}")
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 远程模式：将镜像库配置上传到云端 */
    private fun pushToCloud() {
        AppLogger.i("ControlListFragment", "远程模式：覆盖远程配置")
        Toast.makeText(requireContext(), "正在上传到云端...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val imei = DeviceUtil.getEffectiveSerial()
            if (imei.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "无法获取设备序列号，请在设置中填写", Toast.LENGTH_LONG).show()
                return@launch
            }
            val result = CloudSyncEngine.pushToCloud(imei)
            Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
        }
    }

    /** 从镜像库重新加载列表 */
    private fun reloadFromMirror() {
        scope.launch {
            val db = MirrorDatabase.getInstance(requireContext())
            val list = db.controlListDao().getAll()
            items.clear()
            items.addAll(list)
            filterAndUpdate(statsCardView, tvStatTotalView, tvStatAllowedView, tvStatDisabledView)
        }
    }

    private fun showAddAppDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_app, null)
        val etPackageName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
            com.readboy.control.R.id.etPackageName
        )

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_add_app_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_add_app) { _, _ ->
                val pkg = etPackageName.text?.toString()?.trim() ?: ""
                if (pkg.isEmpty()) {
                    Toast.makeText(requireContext(), "请输入包名", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                addAppToMirror(pkg)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun addAppToMirror(pkg: String) {
        scope.launch {
            val db = MirrorDatabase.getInstance(requireContext())
            // 已存在则不重复添加
            val existing = db.controlListDao().getByPackage(pkg)
            if (existing != null) {
                Toast.makeText(requireContext(), "该应用已存在", Toast.LENGTH_SHORT).show()
                return@launch
            }
            db.controlListDao().insert(
                MirrorControlItem(
                    package_name = pkg,
                    disabled_state = 0,
                    operation = "add",
                    sync_status = 2  // 需要云端上传
                )
            )
            AppLogger.i("ControlListFragment", "添加应用到镜像库: $pkg")
            Toast.makeText(requireContext(), "已添加 $pkg", Toast.LENGTH_SHORT).show()
            loadMirror(statsCardView, tvStatTotalView, tvStatAllowedView, tvStatDisabledView)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ==================== 列表适配器 ====================

    inner class ControlListAdapter(
        context: Context,
        private val data: MutableList<MirrorControlItem>
    ) : ArrayAdapter<MirrorControlItem>(context, 0, data) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_control_list, parent, false)

            val item = data[position]
            val tvPackageName = view.findViewById<TextView>(R.id.tvPackageName)
            val tvAppName = view.findViewById<TextView>(R.id.tvAppName)
            val switchEnabled = view.findViewById<MaterialSwitch>(R.id.switchEnabled)
            val btnRemove = view.findViewById<ImageButton>(R.id.btnRemove)

            tvPackageName.text = item.package_name
            tvAppName.text = if (item.app_name.isNullOrEmpty()) {
                if (item.disabled_state == 1) context.getString(R.string.app_disabled)
                else context.getString(R.string.app_enabled)
            } else {
                item.app_name
            }
            switchEnabled.isChecked = item.disabled_state == 0

            // 开关切换
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                val newItem = item.copy(disabled_state = if (isChecked) 0 else 1, sync_status = 2)
                data[position] = newItem
                scope.launch {
                    val db = MirrorDatabase.getInstance(context)
                    db.controlListDao().update(newItem)
                    AppLogger.d("ControlListFragment", "切换 ${item.package_name} → ${if (isChecked) "允许" else "禁用"}")
                }
            }

            // 删除按钮
            btnRemove.setOnClickListener {
                scope.launch {
                    val db = MirrorDatabase.getInstance(context)
                    db.controlListDao().delete(item)
                    data.removeAt(position)
                    notifyDataSetChanged()
                    AppLogger.i("ControlListFragment", "移除 ${item.package_name}")
                }
            }

            return view
        }
    }
}