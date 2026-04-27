package com.goldprice.app.ui.history

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.goldprice.app.GoldPriceApp
import com.goldprice.app.data.model.Csi300History
import com.goldprice.app.data.model.PriceHistory
import com.goldprice.app.databinding.FragmentHistoryBinding
import com.goldprice.app.ui.common.ChartMarkerView
import com.goldprice.app.ui.common.DualChartMarkerView
import com.goldprice.app.utils.DateUtils

class HistoryFragment : Fragment() {
    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val vm: HistoryViewModel by viewModels {
        HistoryViewModel.Factory((requireActivity().application as GoldPriceApp).repository)
    }

    private var currentDays = 365
    private var allHistory: List<PriceHistory> = emptyList()
    private var csi300Data: List<Csi300History> = emptyList()
    private var showCsi300 = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 时间段按钮
        binding.btn3m.setOnClickListener { setDays(90, "3个月") }
        binding.btn6m.setOnClickListener { setDays(180, "6个月") }
        binding.btn1y.setOnClickListener { setDays(365, "1年") }
        binding.btnAll.setOnClickListener { setDays(Int.MAX_VALUE, "全部") }

        // 叠加大盘走势
        binding.btnToggleCsi300.setOnClickListener {
            // 先清除高亮，防止切换时旧Marker在新数据上越界/NPE导致crash
            binding.historyChart.highlightValues(null)
            showCsi300 = !showCsi300
            binding.btnToggleCsi300.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (showCsi300) 0xFF4DABF7.toInt() else 0xFF1A2840.toInt()
            )
            binding.btnToggleCsi300.setTextColor(if (showCsi300) 0xFF000000.toInt() else 0xFF4DABF7.toInt())
            updateChart()
        }

        vm.priceHistory.observe(viewLifecycleOwner) { history ->
            allHistory = history.reversed()
            updateUI()
        }

        vm.csi300History.observe(viewLifecycleOwner) { list ->
            csi300Data = list
            updateChart()
        }
    }

    private fun setDays(days: Int, label: String) {
        currentDays = days
        // 更新按钮高亮
        val btns = listOf(binding.btn3m, binding.btn6m, binding.btn1y, binding.btnAll)
        val days2btn = listOf(90, 180, 365, Int.MAX_VALUE)
        btns.forEachIndexed { i, btn ->
            val active = days2btn[i] == days
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (active) 0xFFF0B429.toInt() else 0xFF1E2A3A.toInt()
            )
            btn.setTextColor(if (active) 0xFF000000.toInt() else 0xFFAAAAAA.toInt())
        }
        updateUI()
    }

    private fun updateUI() {
        val history = if (currentDays == Int.MAX_VALUE) allHistory
        else allHistory.takeLast(currentDays)
        if (history.isEmpty()) return

        val max = history.maxOf { it.price }
        val min = history.minOf { it.price }
        val first = history.first().price
        val last = history.last().price
        val change = if (first > 0) (last - first) / first * 100 else 0.0
        val amplitude = if (min > 0) (max - min) / min * 100 else 0.0

        binding.tvHighPrice.text = String.format("%.2f", max)
        binding.tvLowPrice.text = String.format("%.2f", min)
        binding.tvChangeRate.text = String.format("%+.2f", change) + "%"
        binding.tvChangeRate.setTextColor(if (change >= 0) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
        binding.tvAmplitude.text = String.format("%.2f", amplitude) + "%"

        val label = when (currentDays) {
            90 -> "近3个月"; 180 -> "近6个月"; 365 -> "近1年"; else -> "全部数据"
        }
        binding.tvChartTitle.text = "历史走势（$label）"

        updateChart()
    }

    private fun updateChart() {
        val history = if (currentDays == Int.MAX_VALUE) allHistory
        else allHistory.takeLast(currentDays)
        if (history.isEmpty()) return

        // 切换数据前清除高亮，防止旧Marker访问新数据越界
        binding.historyChart.highlightValues(null)

        val goldEntries = history.mapIndexed { i, h -> Entry(i.toFloat(), h.price.toFloat()) }
        val goldSet = LineDataSet(goldEntries, "黄金 Au99.99 (¥/克)").apply {
            color = 0xFFF0B429.toInt()
            valueTextColor = Color.TRANSPARENT
            lineWidth = 2f
            setDrawCircles(false)
            setDrawFilled(true)
            fillColor = 0x33F0B429.toInt()
            axisDependency = YAxis.AxisDependency.LEFT
        }

        val labels = history.map { DateUtils.formatDate(it.dateKey) }
        val lineData = LineData(goldSet)

        // CSI300叠加（右轴）
        if (showCsi300 && csi300Data.isNotEmpty()) {
            val goldDates = history.map { it.dateKey }.toSet()
            val csiFiltered = csi300Data.filter { it.dateKey in goldDates }
            if (csiFiltered.isNotEmpty()) {
                // 将CSI300日期对齐到黄金数据的索引
                val dateToIdx = history.mapIndexed { i, h -> h.dateKey to i }.toMap()
                val csiEntries = csiFiltered.mapNotNull { c ->
                    dateToIdx[c.dateKey]?.let { i -> Entry(i.toFloat(), c.closePrice.toFloat()) }
                }
                val csiSet = LineDataSet(csiEntries, "沪深300（右轴）").apply {
                    color = 0xFF4DABF7.toInt()
                    valueTextColor = Color.TRANSPARENT
                    lineWidth = 1.5f
                    setDrawCircles(false)
                    setDrawFilled(false)
                    axisDependency = YAxis.AxisDependency.RIGHT
                    enableDashedLine(10f, 5f, 0f)
                }
                lineData.addDataSet(csiSet)
            }
        }

        binding.historyChart.apply {
            data = lineData
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                textColor = 0xFFAAAAAA.toInt()
                labelCount = minOf(6, labels.size)
                position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
            }
            axisLeft.apply {
                textColor = 0xFFF0B429.toInt()
                setDrawGridLines(true)
                gridColor = 0x22FFFFFF.toInt()
            }
            axisRight.apply {
                isEnabled = showCsi300
                textColor = 0xFF4DABF7.toInt()
                setDrawGridLines(false)
            }
            legend.apply {
                isEnabled = true
                textColor = 0xFFAAAAAA.toInt()
                textSize = 10f
            }
            description.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            // 叠加大盘时使用双值气泡，否则用单值气泡
            if (showCsi300 && csi300Data.isNotEmpty()) {
                val dateToIdx = history.mapIndexed { i, h -> h.dateKey to i }.toMap()
                val goldDates = history.map { it.dateKey }.toSet()
                val csiFiltered = csi300Data.filter { it.dateKey in goldDates }
                val csi300ValMap = csiFiltered.mapNotNull { c ->
                    dateToIdx[c.dateKey]?.let { i -> i to c.closePrice.toFloat() }
                }.toMap()
                marker = DualChartMarkerView(requireContext(), labels, csi300ValMap)
            } else {
                marker = ChartMarkerView(requireContext(), labels, "¥", "元/克")
            }
            isHighlightPerTapEnabled = true
            animateX(300)
            invalidate()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
