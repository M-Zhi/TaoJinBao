package com.goldprice.app.ui.realtime

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.goldprice.app.GoldPriceApp
import com.goldprice.app.data.model.PriceHistory
import com.goldprice.app.databinding.FragmentRealtimeBinding
import com.goldprice.app.ui.common.ChartMarkerView
import com.goldprice.app.utils.DateUtils
import java.text.DecimalFormat

class RealtimeFragment : Fragment() {
    private var _binding: FragmentRealtimeBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: RealtimeViewModel
    private val df = DecimalFormat("#,##0.00")
    private val dfPct = DecimalFormat("+#,##0.00;-#,##0.00")

    // 积累今日实时价格点（时间 → 价格）
    private val todayPricePoints = mutableListOf<Pair<String, Double>>()
    private var lastTodayKey: String = ""
    // 标记主图是否已首次渲染（首次播动画，后续只刷新数据不重新动画）
    private var chartFirstDraw = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRealtimeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val repo = (requireActivity().application as GoldPriceApp).repository
        // 绑定到 Activity，切换 Tab 时不重建 ViewModel，避免图表重绘
        viewModel = ViewModelProvider(requireActivity(), RealtimeViewModel.Factory(repo))[RealtimeViewModel::class.java]

        setupSwipeRefresh()
        observeViewModel()
        // 设置 AI 预测标签为明日具体日期
        binding.tvPredLabel.text = "AI 预测（${DateUtils.formatDate(DateUtils.daysFromNowKey(1))}）"
        viewModel.startAutoRefresh()
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(
            android.R.color.holo_orange_dark,
            android.R.color.holo_orange_light
        )
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            if (!loading) binding.swipeRefresh.isRefreshing = false
        }

        viewModel.currentPrice.observe(viewLifecycleOwner) { price ->
            if (price != null) {
                binding.tvCurrentPrice.text = "${df.format(price)} 元/克"
                binding.tvCurrentPrice.setTextColor(Color.parseColor("#F0B429"))
                // 追加到今日实时图
                val todayKey = DateUtils.todayKey()
                if (lastTodayKey != todayKey) {
                    todayPricePoints.clear()
                    lastTodayKey = todayKey
                }
                val nowLabel = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                // 避免重复时间点
                if (todayPricePoints.isEmpty() || todayPricePoints.last().first != nowLabel) {
                    todayPricePoints.add(nowLabel to price)
                } else {
                    todayPricePoints[todayPricePoints.lastIndex] = nowLabel to price
                }
                updateTodayChart()
            } else {
                binding.tvCurrentPrice.text = "--"
                binding.tvCurrentPrice.setTextColor(Color.parseColor("#AAAAAA"))
            }
        }

        viewModel.priceChange.observe(viewLifecycleOwner) { pct ->
            binding.tvPriceChange.text = "${dfPct.format(pct)}%"
            val color = if (pct >= 0) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
            binding.tvPriceChange.setTextColor(color)
        }

        viewModel.statusMessage.observe(viewLifecycleOwner) { msg ->
            binding.tvMarketStatus.text = msg
        }

        viewModel.prediction.observe(viewLifecycleOwner) { pred ->
            if (pred != null) {
                binding.tvPred1d.text = df.format(pred.first)
                // 预测值就绪后重绘7日走势图，确保AI预测虚线点立即显示
                val history = viewModel.priceHistory.value
                if (!history.isNullOrEmpty()) {
                    updateChart(history.reversed().takeLast(7))
                }
            }
        }

        viewModel.priceHistory.observe(viewLifecycleOwner) { history ->
            if (history.isNotEmpty()) {
                // 只展示近7天
                updateChart(history.reversed().takeLast(7))
            }
        }
    }

    private fun updateChart(history: List<PriceHistory>) {
        val entries = history.mapIndexed { i, h -> Entry(i.toFloat(), h.price.toFloat()) }
        val dataSet = LineDataSet(entries, "开盘价").apply {
            color = Color.parseColor("#F0B429")
            valueTextColor = Color.TRANSPARENT
            lineWidth = 2.5f
            setDrawCircles(true)
            circleRadius = 3f
            circleHoleRadius = 1.5f
            setCircleColor(Color.parseColor("#F0B429"))
            setDrawFilled(true)
            fillColor = Color.parseColor("#33F0B429")
        }

        // 可变标签列表，可能追加"明日预测"
        val labels = history.map { DateUtils.formatDate(it.dateKey) }.toMutableList()
        val lineData = LineData(dataSet)

        // 追加AI预测明日值（虚线）
        val pred1d = viewModel.prediction.value?.first
        if (pred1d != null && pred1d > 0) {
            val predIdx = history.size.toFloat()
            val predEntry = Entry(predIdx, pred1d.toFloat())
            // 连接线：从最后一个真实点到预测点
            val linkEntries = listOf(
                Entry((history.size - 1).toFloat(), history.last().price.toFloat()),
                predEntry
            )
            val predSet = LineDataSet(linkEntries, "AI预测（明日）").apply {
                color = Color.parseColor("#A78BFA")    // 紫色区分
                valueTextColor = Color.TRANSPARENT
                lineWidth = 2f
                setDrawCircles(true)
                circleRadius = 4f
                circleHoleRadius = 2f
                setCircleColor(Color.parseColor("#A78BFA"))
                setDrawFilled(false)
                enableDashedLine(12f, 6f, 0f)         // 虚线
            }
            lineData.addDataSet(predSet)
            labels.add("AI预测")
        }

        binding.priceChart.apply {
            data = lineData
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                textColor = Color.parseColor("#AAAAAA")
                textSize = 10f
                granularity = 1f
                labelCount = labels.size
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                setAvoidFirstLastClipping(true)
            }
            axisLeft.apply {
                textColor = Color.parseColor("#AAAAAA")
                setDrawGridLines(true)
                gridColor = 0x22FFFFFF.toInt()
            }
            axisRight.isEnabled = false
            legend.apply {
                isEnabled = pred1d != null && pred1d > 0
                textColor = Color.parseColor("#AAAAAA")
                textSize = 10f
            }
            description.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            marker = ChartMarkerView(requireContext(), labels, "¥", "元/克")
            isHighlightPerTapEnabled = true
            // 首次渲染才播入场动画，后续切换回来只刷新数据
            if (chartFirstDraw) {
                chartFirstDraw = false
                animateX(500)
            } else {
                invalidate()
            }
        }
    }

    private fun updateTodayChart() {
        if (todayPricePoints.size < 2) {
            // 数据不足时显示提示文字
            binding.todayChart.visibility = View.GONE
            binding.tvTodayNoData.visibility = View.VISIBLE
            return
        }
        binding.todayChart.visibility = View.VISIBLE
        binding.tvTodayNoData.visibility = View.GONE

        val entries = todayPricePoints.mapIndexed { i, (_, price) ->
            Entry(i.toFloat(), price.toFloat())
        }
        val labels = todayPricePoints.map { it.first }
        val minPrice = todayPricePoints.minOf { it.second }
        val maxPrice = todayPricePoints.maxOf { it.second }
        binding.tvTodayRange.text = "最低 ${df.format(minPrice)} · 最高 ${df.format(maxPrice)}"

        val dataSet = LineDataSet(entries, "今日行情").apply {
            color = Color.parseColor("#4DABF7")
            valueTextColor = Color.TRANSPARENT
            lineWidth = 2f
            setDrawCircles(false)
            setDrawFilled(true)
            fillColor = Color.parseColor("#224DABF7")
        }
        binding.todayChart.apply {
            data = LineData(dataSet)
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                textColor = Color.parseColor("#AAAAAA")
                textSize = 9f
                granularity = 1f
                labelCount = minOf(6, labels.size)
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
            }
            axisLeft.apply {
                textColor = Color.parseColor("#AAAAAA")
                setDrawGridLines(true)
                gridColor = 0x22FFFFFF.toInt()
            }
            axisRight.isEnabled = false
            legend.isEnabled = false
            description.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            marker = ChartMarkerView(requireContext(), labels, "¥", "元/克")
            isHighlightPerTapEnabled = true
            invalidate()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopAutoRefresh()
    }

    override fun onResume() {
        super.onResume()
        viewModel.startAutoRefresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
