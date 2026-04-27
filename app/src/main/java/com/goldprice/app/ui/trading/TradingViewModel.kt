package com.goldprice.app.ui.trading

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import com.goldprice.app.data.model.Account
import com.goldprice.app.data.model.TradeRecordV2
import com.goldprice.app.data.repository.GoldRepository
import com.goldprice.app.utils.DateUtils
import kotlinx.coroutines.launch

data class AiStrategyParams(
    val buyThreshold: Double = 0.003,
    val positionLimit: Double = 0.6,
    val buyRatio: Double = 0.3,
    val sellThreshold: Double = 0.003,
    val sellRatio: Double = 0.5,
    val stopLoss: Double = 0.03
)

class TradingViewModel(app: Application, private val repo: GoldRepository) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ai_strategy_params", Context.MODE_PRIVATE)

    val accounts: LiveData<List<Account>> = repo.getAllAccountsLive()

    private val _dcaReturns = MutableLiveData<Triple<Double, Double, Double>>()
    val dcaReturns: LiveData<Triple<Double, Double, Double>> = _dcaReturns

    private val _currentPrice = MutableLiveData<Double?>()
    val currentPrice: LiveData<Double?> = _currentPrice

    private val _message = MutableLiveData<String?>()
    val message: LiveData<String?> = _message

    // 从SharedPreferences恢复AI策略参数
    var aiParams: AiStrategyParams
        get() = AiStrategyParams(
            buyThreshold  = prefs.getFloat("buyThreshold",  0.003f).toDouble(),
            positionLimit = prefs.getFloat("positionLimit", 0.6f).toDouble(),
            buyRatio      = prefs.getFloat("buyRatio",      0.3f).toDouble(),
            sellThreshold = prefs.getFloat("sellThreshold", 0.003f).toDouble(),
            sellRatio     = prefs.getFloat("sellRatio",     0.5f).toDouble(),
            stopLoss      = prefs.getFloat("stopLoss",      0.03f).toDouble()
        )
        set(v) {
            prefs.edit()
                .putFloat("buyThreshold",  v.buyThreshold.toFloat())
                .putFloat("positionLimit", v.positionLimit.toFloat())
                .putFloat("buyRatio",      v.buyRatio.toFloat())
                .putFloat("sellThreshold", v.sellThreshold.toFloat())
                .putFloat("sellRatio",     v.sellRatio.toFloat())
                .putFloat("stopLoss",      v.stopLoss.toFloat())
                .apply()
        }

    init {
        loadDcaReturns()
        loadCurrentPrice()
    }

    fun refresh() {
        loadDcaReturns()
        loadCurrentPrice()
    }

    private fun loadDcaReturns() {
        viewModelScope.launch {
            try {
                _dcaReturns.value = repo.computeDcaReturns()
            } catch (e: Exception) {
                _dcaReturns.value = Triple(0.0, 0.0, 0.0)
            }
        }
    }

    private fun loadCurrentPrice() {
        viewModelScope.launch {
            val last = repo.getLastKnownPrice()
            _currentPrice.value = last?.price
        }
    }

    fun buyGold(accountId: Long, grams: Double) {
        viewModelScope.launch {
            val price = _currentPrice.value ?: run { _message.value = "无法获取当前金价"; return@launch }
            val accounts = repo.getAllAccounts()
            val account = accounts.find { it.id == accountId } ?: return@launch
            val cost = price * grams
            if (account.cashBalance < cost) { _message.value = "现金不足"; return@launch }
            repo.recordTrade(TradeRecordV2(accountId = accountId, dateKey = DateUtils.todayKey(),
                action = "BUY", price = price, grams = grams, amount = cost))
            _message.value = "买入 ${String.format("%.2f", grams)}克 @ ¥${String.format("%.2f", price)}"
        }
    }

    fun sellGold(accountId: Long, grams: Double) {
        viewModelScope.launch {
            val price = _currentPrice.value ?: run { _message.value = "无法获取当前金价"; return@launch }
            val accounts = repo.getAllAccounts()
            val account = accounts.find { it.id == accountId } ?: return@launch
            if (account.holdingGrams < grams) { _message.value = "持仓不足"; return@launch }
            val amount = price * grams
            repo.recordTrade(TradeRecordV2(accountId = accountId, dateKey = DateUtils.todayKey(),
                action = "SELL", price = price, grams = grams, amount = amount))
            _message.value = "卖出 ${String.format("%.2f", grams)}克 @ ¥${String.format("%.2f", price)}"
        }
    }

    fun resetAccount(accountId: Long) {
        viewModelScope.launch {
            repo.resetAccount(accountId)
            _message.value = "账户已重置"
        }
    }

    fun deleteAccount(account: Account) {
        viewModelScope.launch {
            if (account.type == "AI") { _message.value = "AI账户不可删除"; return@launch }
            repo.deleteAccount(account)
        }
    }

    fun addAccount(name: String, color: String) {
        viewModelScope.launch {
            if (name.isBlank()) { _message.value = "账户名不能为空"; return@launch }
            repo.addAccount(Account(name = name, type = "MANUAL", cashBalance = 100000.0, color = color))
            _message.value = "账户「$name」已创建"
        }
    }

    fun updateAccountName(account: Account, newName: String, newColor: String) {
        viewModelScope.launch {
            repo.updateAccount(account.copy(name = newName, color = newColor))
        }
    }

    fun runAiStrategy() {
        viewModelScope.launch {
            val price = _currentPrice.value ?: return@launch
            val pred = repo.getLatestPrediction()
            val pred1d = pred?.predictedPrice1d ?: price
            val p = aiParams
            repo.runAiStrategy(price, pred1d,
                buyThreshold = p.buyThreshold,
                positionLimit = p.positionLimit,
                buyRatio = p.buyRatio,
                sellThreshold = p.sellThreshold,
                sellRatio = p.sellRatio,
                stopLoss = p.stopLoss
            )
        }
    }

    fun clearMessage() { _message.value = null }

    class Factory(private val app: Application, private val repo: GoldRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return TradingViewModel(app, repo) as T
        }
    }
}
