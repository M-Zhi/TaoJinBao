package com.goldprice.app.utils

import java.text.SimpleDateFormat
import java.util.*

object DateUtils {
    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun todayKey(): String = sdf.format(Date())

    fun daysAgoKey(days: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -days)
        return sdf.format(cal.time)
    }

    fun daysFromNowKey(days: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, days)
        return sdf.format(cal.time)
    }

    fun formatDate(dateKey: String): String {
        return try {
            val date = sdf.parse(dateKey) ?: return dateKey
            SimpleDateFormat("MM/dd", Locale.getDefault()).format(date)
        } catch (e: Exception) { dateKey }
    }

    fun formatPrice(price: Double): String = String.format(Locale.getDefault(), "%.2f", price)
}

/**
 * 上海黄金交易所交易时段
 * 日盘：周一至周五 09:00~15:30
 * 夜盘：周一至周四 20:00~次日 02:30（周五无夜盘）
 */
object TradingHoursUtils {
    fun currentStatus(): MarketStatus {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        val day = cal.get(Calendar.DAY_OF_WEEK)  // 1=SUN, 2=MON, …, 7=SAT
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val timeInMinutes = hour * 60 + minute

        // 日盘：周一至周五 09:00~15:30
        if (day in Calendar.MONDAY..Calendar.FRIDAY) {
            if (timeInMinutes in (9 * 60)..(15 * 60 + 30)) return MarketStatus.OPEN
        }

        // 夜盘：周一至周四 20:00~23:59 + 周二至周五 00:00~02:30（次日凌晨）
        // 20:00~23:59: 周一(MON)~周四(THU)
        if (day in Calendar.MONDAY..Calendar.THURSDAY) {
            if (timeInMinutes >= 20 * 60) return MarketStatus.OPEN
        }
        // 00:00~02:30: 周二(TUE)~周五(FRI) 的凌晨（即周一~周四夜盘延续）
        if (day in Calendar.TUESDAY..Calendar.FRIDAY) {
            if (timeInMinutes <= 2 * 60 + 30) return MarketStatus.OPEN
        }

        return MarketStatus.CLOSED
    }

    enum class MarketStatus { OPEN, CLOSED }

    fun statusLabel(): String = when (currentStatus()) {
        MarketStatus.OPEN -> "交易中"
        MarketStatus.CLOSED -> "已收盘"
    }
}

data class PredictionResult(
    val price1d: Double,
    val price2d: Double,
    val price3d: Double
)

/**
 * 预测算法：Holt双指数平滑（Holt's Exponential Smoothing）
 *
 * 相比简单加权移动平均，同时追踪价格水平（L）和趋势方向（T），
 * 对趋势反转的识别更及时，短期预测准确率更高。
 *
 * 公式：
 *   Lₜ = α·pₜ + (1-α)·(Lₜ₋₁ + Tₜ₋₁)   // 水平平滑
 *   Tₜ = β·(Lₜ - Lₜ₋₁) + (1-β)·Tₜ₋₁   // 趋势平滑
 *   预测(h步) = Lₙ + h·Tₙ
 *
 * 超参数默认值：α=0.3（价格平滑），β=0.1（趋势平滑）
 * 阻尼因子 φ=0.85：防止趋势无限外推，让远期预测均值回归
 */
object PredictionUtils {
    fun predict(prices: List<Double>, alpha: Double = 0.3, beta: Double = 0.1, phi: Double = 0.85): PredictionResult {
        if (prices.size < 3) {
            val last = prices.lastOrNull() ?: 700.0
            return PredictionResult(last, last, last)
        }

        // 用最近60个价格点（过多历史反而引入噪声）
        val data = prices.takeLast(minOf(60, prices.size))

        // 初始化：L₀ = 前4天均值，T₀ = 均值变化
        var l = data.take(4).average()
        var t = (data[1] - data[0] + data[2] - data[1] + data[3] - data[2]) / 3.0

        // Holt双指数平滑迭代
        for (i in 1 until data.size) {
            val lPrev = l
            l = alpha * data[i] + (1 - alpha) * (l + t)
            t = beta * (l - lPrev) + (1 - beta) * t
        }

        // 带阻尼的多步预测：φ^h 衰减趋势，避免直线外推
        fun forecast(h: Int): Double {
            var phiSum = 0.0
            repeat(h) { k -> phiSum += Math.pow(phi, (k + 1).toDouble()) }
            return l + phiSum * t
        }

        return PredictionResult(
            price1d = forecast(1),
            price2d = forecast(2),
            price3d = forecast(3)
        )
    }
}
