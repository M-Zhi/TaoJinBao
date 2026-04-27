package com.goldprice.app.ui.realtime

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.*
import com.goldprice.app.data.repository.GoldRepository
import com.goldprice.app.utils.TradingHoursUtils
import kotlinx.coroutines.launch

class RealtimeViewModel(private val repo: GoldRepository) : ViewModel() {

    private val _currentPrice = MutableLiveData<Double?>()
    val currentPrice: LiveData<Double?> = _currentPrice

    private val _priceChange = MutableLiveData<Double>()
    val priceChange: LiveData<Double> = _priceChange

    private val _prediction = MutableLiveData<Triple<Double, Double, Double>?>()
    val prediction: LiveData<Triple<Double, Double, Double>?> = _prediction

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _marketStatus = MutableLiveData<TradingHoursUtils.MarketStatus>()
    val marketStatus: LiveData<TradingHoursUtils.MarketStatus> = _marketStatus

    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage

    val priceHistory = repo.getAllPricesLive()

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            val interval = when (TradingHoursUtils.currentStatus()) {
                TradingHoursUtils.MarketStatus.OPEN -> 30_000L
                TradingHoursUtils.MarketStatus.CLOSED -> 300_000L
            }
            refresh()
            handler.postDelayed(this, interval)
        }
    }

    fun startAutoRefresh() {
        // 进入页面立即刷新一次，然后开始定时轮询
        refresh()
        handler.postDelayed(refreshRunnable, 30_000L)
    }

    fun stopAutoRefresh() {
        handler.removeCallbacks(refreshRunnable)
    }

    fun refresh() {
        _isLoading.value = true
        val status = TradingHoursUtils.currentStatus()
        _marketStatus.value = status

        viewModelScope.launch {
            if (status == TradingHoursUtils.MarketStatus.OPEN) {
                // 开市：尝试联网获取
                val livePrice = repo.fetchAndSaveCurrentPrice()
                if (livePrice != null) {
                    updatePrice(livePrice)
                    _statusMessage.value = "实时报价"
                } else {
                    loadLastKnownPrice()
                    _statusMessage.value = "网络异常，显示历史价"
                }
            } else {
                // 休市：使用本地最后收盘价
                loadLastKnownPrice()
                _statusMessage.value = "休市中"
            }
            // 计算预测
            try {
                val pred = repo.computeAndSavePrediction()
                _prediction.value = pred
            } catch (e: Exception) {
                // 预测失败不影响主流程
            }
            _isLoading.value = false
        }
    }

    private suspend fun loadLastKnownPrice() {
        val last = repo.getLastKnownPrice()
        if (last != null) {
            updatePrice(last.price)
        } else {
            _currentPrice.value = null
        }
    }

    private suspend fun updatePrice(price: Double) {
        _currentPrice.value = price
        val history = repo.getRecentPrices(2)
        val prevPrice = if (history.size >= 2) history[1].price else price
        _priceChange.value = if (prevPrice > 0) (price - prevPrice) / prevPrice * 100 else 0.0
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoRefresh()
    }

    class Factory(private val repo: GoldRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return RealtimeViewModel(repo) as T
        }
    }
}
