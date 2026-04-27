package com.goldprice.app.ui.deviation

import androidx.lifecycle.*
import com.goldprice.app.data.model.PredictionRecord
import com.goldprice.app.data.model.PriceHistory
import com.goldprice.app.data.repository.GoldRepository
import com.goldprice.app.utils.DateUtils
import kotlinx.coroutines.launch

data class DeviationStats(
    val totalPredictions: Int,
    val accurateCount1d: Int,    // 预测方向正确（涨跌方向）
    val avgError1d: Double,      // 平均绝对误差%
    val maxError1d: Double,
    val predictions: List<PredictionRecord>,
    val priceMap: Map<String, Double>  // dateKey -> actual price
)

class DeviationViewModel(private val repo: GoldRepository) : ViewModel() {
    private val _stats = MutableLiveData<DeviationStats?>()
    val stats: LiveData<DeviationStats?> = _stats

    init { loadData() }

    fun loadData() {
        viewModelScope.launch {
            val predictions = repo.getPredictionsForDeviation(DateUtils.daysAgoKey(90))
            val history = repo.getRecentPrices(90)
            val priceMap = history.associate { it.dateKey to it.price }

            if (predictions.isEmpty()) { _stats.value = null; return@launch }

            var correctCount = 0
            var totalError = 0.0
            var maxError = 0.0
            var count = 0

            predictions.forEach { pred ->
                val actualPrice = priceMap[pred.predictionDate] ?: return@forEach
                // 找前一天价格（用于判断方向）
                val predDate = pred.createdDate
                val prevPrice = priceMap[predDate] ?: return@forEach
                val predictedChange = pred.predictedPrice1d - prevPrice
                val actualChange = actualPrice - prevPrice
                if ((predictedChange > 0 && actualChange > 0) || (predictedChange < 0 && actualChange < 0)) correctCount++
                val errPct = if (prevPrice > 0) kotlin.math.abs(pred.predictedPrice1d - actualPrice) / prevPrice * 100 else 0.0
                totalError += errPct
                maxError = maxOf(maxError, errPct)
                count++
            }

            _stats.value = DeviationStats(
                totalPredictions = predictions.size,
                accurateCount1d = correctCount,
                avgError1d = if (count > 0) totalError / count else 0.0,
                maxError1d = maxError,
                predictions = predictions,
                priceMap = priceMap
            )
        }
    }

    class Factory(private val repo: GoldRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return DeviationViewModel(repo) as T
        }
    }
}
