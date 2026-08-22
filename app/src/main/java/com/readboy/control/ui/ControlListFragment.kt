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

        // 远程模式：更新流程提示 + 禁用本地按钮
        val tvFlowHint = view.findViewById<TextView>(R.id.tvFlowHint)
        if (DeviceUtil.isRemoteMode()) {
            tvFlowHint.text = "远程模式：仅支持云端操作"
            view.findViewById<View>(R.id.btnPullLocal).isEnabled = false
            view.findViewById<View>(R.id.btnPushLocal).isEnabled = false
        }

        adapter = ControlListAdapter(requireContext(), filteredItems)
        listView.adapter = adapter

        // 拉取/推送按钮
        view.findViewById<View>(R.id.btnPullLocal).setOnClickListener {
            if (DeviceUtil.isRemoteMode()) {
                Toast.makeText(requireContext(), "远程模式：仅支持云端操作（密码管理页）", Toast.LENGTH_LONG).show()
            } else {
                pullFromProvider()
            }
        }
        view.findViewById<View>(R.id.btnPushLocal).setOnClickListener {
            if (DeviceUtil.isRemoteMode()) {
                Toast.makeText(requireContext(), "远程模式：仅支持云端操作（密码管理页）", Toast.LENGTH_LONG).show()
            } else {
                pushToProvider()
            }
        }

        // 远程模式提示
        if (DeviceUtil.isRemoteMode()) {
            val hint = "远程模式 - 未检测到本机家长管理\n所有操作通过 API 直接发送到云端"
            emptyView.text = hint
            Toast.makeText(requireContext(), hint, Toast.LENGTH_LONG).show()
            AppLogger.i("ControlListFragment", "远程模式，仅显示镜像库")
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