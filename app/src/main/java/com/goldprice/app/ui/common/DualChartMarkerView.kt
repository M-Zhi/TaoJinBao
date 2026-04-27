package com.goldprice.app.ui.common

import android.content.Context
import android.view.View
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.utils.MPPointF
import com.goldprice.app.R

/**
 * 双值气泡：叠加沪深300时同时显示金价 + 大盘指数
 * @param labels        x轴索引对应日期列表
 * @param csi300Map     dateKey → CSI300 closePrice（null表示未叠加大盘）
 */
class DualChartMarkerView(
    context: Context,
    private val labels: List<String>,
    private val csi300Values: Map<Int, Float>   // index → csi300 price
) : MarkerView(context, R.layout.marker_chart_dual) {

    private val tvDate: TextView = findViewById(R.id.tv_marker_date)
    private val tvGold: TextView = findViewById(R.id.tv_marker_gold)
    private val tvCsi: TextView = findViewById(R.id.tv_marker_csi)

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        if (e == null) return
        val idx = e.x.toInt().coerceIn(0, labels.size - 1)
        tvDate.text = if (labels.isNotEmpty()) labels[idx] else ""
        tvGold.text = "金价  ¥${String.format("%,.2f", e.y)} 元/克"
        val csiVal = csi300Values[idx]
        if (csiVal != null) {
            tvCsi.text = "沪深300  ${String.format("%,.0f", csiVal)} 点"
            tvCsi.visibility = View.VISIBLE
        } else {
            tvCsi.visibility = View.GONE
        }
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF {
        return MPPointF(-(width / 2f), -height.toFloat() - 8f)
    }
}
