package com.goldprice.app.ui.common

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import com.goldprice.app.R

/**
 * 图表触摸标记气泡：显示日期 + 数值
 * @param labels  x轴索引对应的日期字符串列表
 * @param prefix  数值前缀，如 "¥" 或 ""
 * @param suffix  数值后缀，如 "元/克" 或 "点"
 */
class ChartMarkerView(
    context: Context,
    private val labels: List<String>,
    private val prefix: String = "¥",
    private val suffix: String = ""
) : MarkerView(context, R.layout.marker_chart) {

    private val tvDate: TextView = findViewById(R.id.tv_marker_date)
    private val tvValue: TextView = findViewById(R.id.tv_marker_value)

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        if (e == null) return
        val idx = e.x.toInt().coerceIn(0, labels.size - 1)
        tvDate.text = if (labels.isNotEmpty()) labels[idx] else ""
        tvValue.text = "$prefix${String.format("%,.2f", e.y)}$suffix"
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF {
        return MPPointF(-(width / 2f), -height.toFloat() - 8f)
    }
}
