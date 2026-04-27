package com.goldprice.app.ui.trading

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.goldprice.app.GoldPriceApp
import com.goldprice.app.R
import com.goldprice.app.data.model.Account
import com.goldprice.app.databinding.FragmentTradingBinding
import com.google.android.material.snackbar.Snackbar

class TradingFragment : Fragment() {

    private var _binding: FragmentTradingBinding? = null
    private val binding get() = _binding!!
    private val vm: TradingViewModel by viewModels {
        TradingViewModel.Factory(
            requireActivity().application,
            (requireActivity().application as GoldPriceApp).repository
        )
    }
    private val expandedMap = mutableMapOf<Long, Boolean>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTradingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.swipeRefresh.setColorSchemeColors(0xFFF0B429.toInt())
        binding.swipeRefresh.setOnRefreshListener {
            vm.refresh()
            binding.swipeRefresh.isRefreshing = false
        }

        binding.fabAddAccount.setOnClickListener { showAddAccountDialog() }

        vm.dcaReturns.observe(viewLifecycleOwner) { (csi300, nasdaq, gold) ->
            binding.tvDcaCsi300Return.text = "${String.format("%+.1f", csi300)}%"
            binding.tvDcaCsi300Return.setTextColor(if (csi300 >= 0) 0xFF9C88FF.toInt() else 0xFFFF6B6B.toInt())
            binding.tvDcaNasdaqReturn.text = "${String.format("%+.1f", nasdaq)}%"
            binding.tvDcaNasdaqReturn.setTextColor(if (nasdaq >= 0) 0xFF4DABF7.toInt() else 0xFFFF6B6B.toInt())
            binding.tvDcaGoldReturn.text = "${String.format("%+.1f", gold)}%"
            binding.tvDcaGoldReturn.setTextColor(if (gold >= 0) 0xFFF0B429.toInt() else 0xFFFF6B6B.toInt())
        }

        vm.accounts.observe(viewLifecycleOwner) { accounts -> renderAccounts(accounts) }

        vm.currentPrice.observe(viewLifecycleOwner) { vm.accounts.value?.let { renderAccounts(it) } }

        vm.message.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrEmpty()) {
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                vm.clearMessage()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    private fun renderAccounts(accounts: List<Account>) {
        binding.accountContainer.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val price = vm.currentPrice.value ?: 0.0
        for (account in accounts) {
            binding.accountContainer.addView(buildAccountCard(inflater, account, price))
        }
    }

    private fun buildAccountCard(inflater: LayoutInflater, account: Account, currentPrice: Double): View {
        val card = inflater.inflate(R.layout.item_account_card, binding.accountContainer, false)
        val accentColor = try { Color.parseColor(account.color) } catch (e: Exception) { 0xFF4DABF7.toInt() }
        val isExpanded = expandedMap[account.id] ?: false

        card.findViewById<View>(R.id.view_color_bar).setBackgroundColor(accentColor)
        card.findViewById<TextView>(R.id.tv_account_name).text = account.name
        val tvType = card.findViewById<TextView>(R.id.tv_account_type)
        tvType.text = if (account.type == "AI") "AI策略" else "手动"
        tvType.setBackgroundColor(if (account.type == "AI") 0x33F0B429.toInt() else 0x334DABF7.toInt())
        tvType.setTextColor(if (account.type == "AI") 0xFFF0B429.toInt() else 0xFF4DABF7.toInt())

        val marketValue = account.holdingGrams * currentPrice
        val totalValue = account.cashBalance + marketValue
        card.findViewById<TextView>(R.id.tv_account_cash).text =
            "现金: ¥${String.format("%,.2f", account.cashBalance)}"
        card.findViewById<TextView>(R.id.tv_account_holding).text =
            "持仓: ${String.format("%.2f", account.holdingGrams)}克  市值: ¥${String.format("%,.2f", marketValue)}"

        val floatPnl = if (account.holdingGrams > 0 && account.avgCostPrice > 0)
            (currentPrice - account.avgCostPrice) * account.holdingGrams else 0.0
        val floatPct = if (account.avgCostPrice > 0) (currentPrice - account.avgCostPrice) / account.avgCostPrice * 100 else 0.0
        val tvFloat = card.findViewById<TextView>(R.id.tv_float_pnl)
        tvFloat.text = "浮盈: ${String.format("%+,.2f", floatPnl)} (${String.format("%+.2f", floatPct)}%)"
        tvFloat.setTextColor(if (floatPnl >= 0) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())

        val totalReturn = totalValue - 100000.0
        val returnPct = totalReturn / 100000.0 * 100
        val tvReturn = card.findViewById<TextView>(R.id.tv_total_return)
        tvReturn.text = "总收益: ${String.format("%+,.2f", totalReturn)} (${String.format("%+.2f", returnPct)}%)"
        tvReturn.setTextColor(if (totalReturn >= 0) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())

        val tvAiStrategy = card.findViewById<TextView>(R.id.tv_ai_strategy)
        val btnAiParams = card.findViewById<Button>(R.id.btn_ai_params)
        if (account.type == "AI") {
            tvAiStrategy.isVisible = true
            btnAiParams.isVisible = true
            val p = vm.aiParams
            tvAiStrategy.text = "买≥${String.format("%.1f", p.buyThreshold*100)}% 仓<${String.format("%.0f", p.positionLimit*100)}% 买${String.format("%.0f", p.buyRatio*100)}%现金 | 卖≥${String.format("%.1f", p.sellThreshold*100)}% 卖${String.format("%.0f", p.sellRatio*100)}%仓 | 止损${String.format("%.1f", p.stopLoss*100)}%"
            btnAiParams.setOnClickListener { showAiParamsDialog() }
        } else {
            tvAiStrategy.isVisible = false
            btnAiParams.isVisible = false
        }

        val expandableLayout = card.findViewById<LinearLayout>(R.id.layout_expandable)
        val btnToggle = card.findViewById<ImageView>(R.id.btn_toggle_expand)
        expandableLayout.isVisible = isExpanded
        btnToggle.rotation = if (isExpanded) 180f else 0f

        val cardHeader = card.findViewById<View>(R.id.layout_card_header)
        cardHeader.setOnClickListener {
            val now = !(expandedMap[account.id] ?: false)
            expandedMap[account.id] = now
            expandableLayout.isVisible = now
            btnToggle.rotation = if (now) 180f else 0f
        }

        val btnBuy = card.findViewById<Button>(R.id.btn_buy)
        val btnSell = card.findViewById<Button>(R.id.btn_sell)
        val btnReset = card.findViewById<Button>(R.id.btn_reset)
        btnBuy.setBackgroundColor(accentColor)
        btnBuy.setOnClickListener { showTradeDialog(account, "BUY") }
        btnSell.setOnClickListener { showTradeDialog(account, "SELL") }
        btnReset.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("重置账户")
                .setMessage("确定将「${account.name}」重置为初始状态（¥100,000）？")
                .setPositiveButton("重置") { _, _ -> vm.resetAccount(account.id) }
                .setNegativeButton("取消", null).show()
        }

        val btnDelete = card.findViewById<ImageView>(R.id.btn_delete)
        val btnEdit = card.findViewById<ImageView>(R.id.btn_edit)
        btnDelete.isVisible = account.type != "AI"
        if (account.type != "AI") {
            btnDelete.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("删除账户")
                    .setMessage("确定删除「${account.name}」？")
                    .setPositiveButton("删除") { _, _ -> vm.deleteAccount(account) }
                    .setNegativeButton("取消", null).show()
            }
        }
        btnEdit.setOnClickListener { showEditAccountDialog(account) }
        return card
    }

    private fun showTradeDialog(account: Account, action: String) {
        val input = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = if (action == "BUY") "买入克数" else "卖出克数"
            setText("1")
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0); addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(if (action == "BUY") "买入黄金" else "卖出黄金")
            .setView(container)
            .setPositiveButton("确认") { _, _ ->
                val g = input.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                if (g > 0) { if (action == "BUY") vm.buyGold(account.id, g) else vm.sellGold(account.id, g) }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showAddAccountDialog() {
        val colors = listOf("#4DABF7", "#9C88FF", "#4CAF50", "#F0B429", "#FF6B6B", "#20C997", "#FD7E14")
        val colorNames = listOf("蓝", "紫", "绿", "金", "红", "青", "橙")
        var selectedColor = colors[0]
        val nameInput = EditText(requireContext()).apply { hint = "账户名称" }
        val rgColors = RadioGroup(requireContext()).apply { orientation = RadioGroup.HORIZONTAL }
        colors.forEachIndexed { i, c ->
            val rb = RadioButton(requireContext()).apply {
                text = colorNames[i]; id = i
                setOnCheckedChangeListener { _, ch -> if (ch) selectedColor = c }
            }
            rgColors.addView(rb)
        }
        rgColors.check(0)
        // 颜色行放入横向滚动容器，防止右侧选项被截断
        val scrollColors = android.widget.HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            addView(rgColors)
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
            addView(nameInput); addView(scrollColors)
        }
        AlertDialog.Builder(requireContext()).setTitle("新建账户").setView(container)
            .setPositiveButton("创建") { _, _ ->
                val name = nameInput.text.toString().trim()
                vm.addAccount(name, selectedColor)
            }.setNegativeButton("取消", null).show()
    }

    private fun showEditAccountDialog(account: Account) {
        val colors = listOf("#4DABF7", "#9C88FF", "#4CAF50", "#F0B429", "#FF6B6B", "#20C997", "#FD7E14")
        val colorNames = listOf("蓝", "紫", "绿", "金", "红", "青", "橙")
        var selectedColor = account.color
        val nameInput = EditText(requireContext()).apply { setText(account.name) }
        val rgColors = RadioGroup(requireContext()).apply { orientation = RadioGroup.HORIZONTAL }
        colors.forEachIndexed { i, c ->
            val rb = RadioButton(requireContext()).apply {
                text = colorNames[i]; id = i
                setOnCheckedChangeListener { _, ch -> if (ch) selectedColor = c }
            }
            rgColors.addView(rb)
        }
        val curIdx = colors.indexOfFirst { it == account.color }.takeIf { it >= 0 } ?: 0
        rgColors.check(curIdx)
        // 颜色行放入横向滚动容器，防止右侧选项被截断
        val scrollColors = android.widget.HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            addView(rgColors)
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
            addView(nameInput); addView(scrollColors)
        }
        AlertDialog.Builder(requireContext()).setTitle("编辑账户").setView(container)
            .setPositiveButton("保存") { _, _ ->
                val n = nameInput.text.toString().trim()
                if (n.isNotEmpty()) vm.updateAccountName(account, n, selectedColor)
            }.setNegativeButton("取消", null).show()
    }

    private fun showAiParamsDialog() {
        val p = vm.aiParams
        val container = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        fun addRow(label: String, value: Double): EditText {
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 6) }
            val tv = TextView(requireContext()).apply { text = label; setTextColor(0xFFCCCCCC.toInt()); textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, -2, 1.5f) }
            val et = EditText(requireContext()).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(String.format("%.2f", value * 100))  // 两位小数
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val unit = TextView(requireContext()).apply { text = "%"; setTextColor(0xFF888888.toInt()); textSize = 13f }
            row.addView(tv); row.addView(et); row.addView(unit)
            container.addView(row); return et
        }
        val etBuy = addRow("买入预涨阈值", p.buyThreshold)
        val etPos = addRow("最大仓位", p.positionLimit)
        val etBuyR = addRow("买入资金比例", p.buyRatio)
        val etSell = addRow("卖出预跌阈值", p.sellThreshold)
        val etSellR = addRow("卖出仓位比例", p.sellRatio)
        val etSL = addRow("止损浮亏线", p.stopLoss)
        AlertDialog.Builder(requireContext()).setTitle("AI策略参数").setView(container)
            .setPositiveButton("保存") { _, _ ->
                vm.aiParams = AiStrategyParams(
                    buyThreshold = (etBuy.text.toString().toDoubleOrNull() ?: 0.3) / 100,
                    positionLimit = (etPos.text.toString().toDoubleOrNull() ?: 60.0) / 100,
                    buyRatio = (etBuyR.text.toString().toDoubleOrNull() ?: 30.0) / 100,
                    sellThreshold = (etSell.text.toString().toDoubleOrNull() ?: 0.3) / 100,
                    sellRatio = (etSellR.text.toString().toDoubleOrNull() ?: 50.0) / 100,
                    stopLoss = (etSL.text.toString().toDoubleOrNull() ?: 3.0) / 100
                )
                vm.accounts.value?.let { renderAccounts(it) }
            }.setNegativeButton("取消", null).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
