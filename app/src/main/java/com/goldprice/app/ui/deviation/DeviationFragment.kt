package com.goldprice.app.ui.deviation

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.goldprice.app.GoldPriceApp
import com.goldprice.app.databinding.FragmentDeviationBinding
import com.goldprice.app.ui.common.ChartMarkerView
import com.goldprice.app.utils.DateUtils

class DeviationFragment : Fragment() {
    private var _binding: FragmentDeviationBinding? = null
    private val binding get() = _binding!!
    private val vm: DeviationViewModel by viewModels {
        DeviationViewModel.Factory((requireActivity().application as GoldPriceApp).repository)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDeviationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        vm.stats.observe(viewLifecycleOwner) { stats ->
            if (stats == null) {
                binding.tvDeviationSummary.text = "暂无预测记录\n开市后预测数据将自动生成"
                binding.tvTotalCount.text = "0"
                binding.tvAccuracyRate.text = "--%"
                binding.tvAvgError.text = "--%"
                binding.tvMaxError.text = "--%"
                return@observe
            }

            binding.tvTotalCount.text = "${stats.totalPredictions}"
            val accRate = if (stats.totalPredictions > 0)
                stats.accurateCount1d.toDouble() / stats.totalPredictions * 100 else 0.0
            binding.tvAccuracyRate.text = String.format("%.1f%%", accRate)
            binding.tvAccuracyRate.setTextColor(if (accRate >= 50) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
            binding.tvAvgError.text = String.format("%.2f%%", stats.avgError1d)
            binding.tvMaxError.text = String.format("%.2f%%", stats.maxError1d)
            binding.tvDeviationSummary.text = "共${stats.totalPredictions}条记录 · 方向准确${stats.accurateCount1d}次 · 算法：Holt 双指数平滑 ⓘ"
            // 点击算法说明，弹出详细介绍
            binding.tvDeviationSummary.setOnClickListener { showAlgoInfoDialog() }

            renderLineChart(stats)
            renderBarChart(stats)
        }
    }

    override fun onResume() {
        super.onResume()
        vm.loadData()
    }

    private fun renderLineChart(stats: DeviationStats) {
        val preds = stats.predictions.takeLast(30)  // 最近30条
        val priceMap = stats.priceMap

        val actualEntries = mutableListOf<Entry>()
        val predEntries = mutableListOf<Entry>()
        val labels = mutableListOf<String>()

        preds.forEachIndexed { i, pred ->
            val actualPrice = priceMap[pred.predictionDate]
            labels.add(DateUtils.formatDate(pred.predictionDate))
            predEntries.add(Entry(i.toFloat(), pred.predictedPrice1d.toFloat()))
            if (actualPrice != null) actualEntries.add(Entry(i.toFloat(), actualPrice.toFloat()))
        }

        val predSet = LineDataSet(predEntries, "预测价格").apply {
            color = 0xFFF0B429.toInt()
            valueTextColor = Color.TRANSPARENT
            lineWidth = 2f
            setDrawCircles(false)
            enableDashedLine(10f, 5f, 0f)
        }

        val actualSet = LineDataSet(actualEntries, "实际价格").apply {
            color = 0xFF4CAF50.toInt()
            valueTextColor = Color.TRANSPARENT
            lineWidth = 2f
            setDrawCircles(false)
            setDrawFilled(true)
            fillColor = 0x224CAF50.toInt()
        }

        binding.deviationChart.apply {
            data = LineData(actualSet, predSet)
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                textColor = 0xFFAAAAAA.toInt()
                labelCount = minOf(6, labels.size)
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
            }
            axisLeft.apply { textColor = 0xFFAAAAAA.toInt(); setDrawGridLines(true); gridColor = 0x22FFFFFF.toInt() }
            axisRight.isEnabled = false
            legend.apply { isEnabled = true; textColor = 0xFFAAAAAA.toInt(); textSize = 10f }
            description.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            marker = ChartMarkerView(requireContext(), labels, "¥", "元/克")
            isHighlightPerTapEnabled = true
            animateX(400)
            invalidate()
        }
    }

    private fun renderBarChart(stats: DeviationStats) {
        val preds = stats.predictions.takeLast(30)
        val priceMap = stats.priceMap

        val barEntries = mutableListOf<BarEntry>()
        val labels = mutableListOf<String>()

        preds.forEachIndexed { i, pred ->
            val actualPrice = priceMap[pred.predictionDate] ?: return@forEachIndexed
            val deviation = pred.predictedPrice1d - actualPrice
            barEntries.add(BarEntry(i.toFloat(), deviation.toFloat()))
            labels.add(DateUtils.formatDate(pred.predictionDate))
        }

        if (barEntries.isEmpty()) return

        val barSet = BarDataSet(barEntries, "偏差（预测-实际）").apply {
            colors = barEntries.map { if (it.y >= 0) 0xFF4DABF7.toInt() else 0xFFFF6B6B.toInt() }
            valueTextColor = Color.TRANSPARENT
        }

        binding.deviationBarChart.apply {
            data = BarData(barSet).apply { barWidth = 0.8f }
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                textColor = 0xFFAAAAAA.toInt()
                labelCount = minOf(6, labels.size)
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
            }
            axisLeft.apply { textColor = 0xFFAAAAAA.toInt(); setDrawGridLines(true); gridColor = 0x22FFFFFF.toInt() }
            axisRight.isEnabled = false
            legend.apply { isEnabled = true; textColor = 0xFFAAAAAA.toInt(); textSize = 10f }
            description.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            marker = ChartMarkerView(requireContext(), labels, "¥", " 偏差")
            isHighlightPerTapEnabled = true
            animateY(400)
            invalidate()
        }
    }

    private fun showAlgoInfoDialog() {
        val message = """
            📊 Holt 双指数平滑算法

            【核心思路】
            同时追踪价格的"水平"（当前均值）和"趋势"（涨跌斜率），两个维度共同决定预测值，对趋势反转响应更及时。

            【算法公式】
            Lₜ = α·pₜ + (1-α)·(Lₜ₋₁ + Tₜ₋₁)   // 水平平滑
            Tₜ = β·(Lₜ - Lₜ₋₁) + (1-β)·Tₜ₋₁   // 趋势平滑
            预测(h步) = Lₙ + φ¹+φ²+…+φʰ × Tₙ

            参数：α=0.3（价格平滑）, β=0.1（趋势平滑）, φ=0.85（阻尼因子，防无限外推）

            【算法优势】
            ✅ 同时考虑价格水平 + 趋势方向
            ✅ 阻尼因子避免趋势过度外推
            ✅ 短期（1天）预测响应趋势转折更及时
            ❌ 无法预测黑天鹅/突发事件
            ❌ 宏观政策导致的跳空缺口仍有较大误差

            【方向准确率说明】
            当预测涨/跌方向与实际一致时计为1次准确，
            准确率 = 正确次数 / 总预测次数 × 100%。
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("预测算法说明")
            .setMessage(message)
            .setPositiveButton("我知道了", null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
