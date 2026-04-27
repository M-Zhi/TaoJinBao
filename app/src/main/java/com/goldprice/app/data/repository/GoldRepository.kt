package com.goldprice.app.data.repository

import android.util.Log
import com.goldprice.app.data.db.*
import com.goldprice.app.data.model.*
import com.goldprice.app.data.seeder.HistoricalDataSeeder
import com.goldprice.app.utils.DateUtils
import com.goldprice.app.utils.PredictionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.concurrent.TimeUnit

class GoldRepository(
    private val priceDao: PriceDao,
    private val predictionDao: PredictionDao,
    private val accountDao: AccountDao,
    private val tradeDao: TradeDao,
    private val csi300Dao: Csi300Dao,
    private val nasdaqDao: NasdaqDao
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // ─── 初始化种子数据 ────────────────────────────────────────────────────────
    suspend fun seedDataIfNeeded() = withContext(Dispatchers.IO) {
        val priceCount = priceDao.countBefore(DateUtils.todayKey())
        if (priceCount < 10) {
            priceDao.insertAll(HistoricalDataSeeder.getGoldPrices())
            Log.d("GoldRepo", "Seeded ${HistoricalDataSeeder.getGoldPrices().size} gold prices")
        }
        if (csi300Dao.count() < 5) {
            csi300Dao.insertAll(HistoricalDataSeeder.getCsi300Prices())
        }
        if (nasdaqDao.count() < 5) {
            nasdaqDao.insertAll(HistoricalDataSeeder.getNasdaqPrices())
        }
        ensureDefaultAccounts()
    }

    suspend fun ensureDefaultAccounts() = withContext(Dispatchers.IO) {
        val count = accountDao.count()
        if (count == 0) {
            accountDao.insert(Account(name = "AI 策略账户", type = "AI",
                cashBalance = 100000.0, holdingGrams = 0.0, avgCostPrice = 0.0,
                color = "#F0B429", isDefault = true))
            accountDao.insert(Account(name = "我的账户", type = "MANUAL",
                cashBalance = 100000.0, holdingGrams = 0.0, avgCostPrice = 0.0,
                color = "#4DABF7", isDefault = false))
            Log.d("GoldRepo", "Created default accounts")
        }
    }

    // ─── 实时金价（多接口链式兜底）─────────────────────────────────────────────
    // 返回 Pair(openPrice?, currentPrice)：
    //   openPrice = null 表示今日尚未开盘（盘前），此时不向 DB 写入，避免脏数据
    //   currentPrice 用于首页实时展示（由 ViewModel 判断休市不调用此函数）
    suspend fun fetchAndSaveCurrentPrice(): Double? = withContext(Dispatchers.IO) {
        val result = fetchGoldPriceSina()
            ?: fetchGoldPriceEastMoney()
            ?: fetchGoldPriceSGE()
        if (result != null) {
            val (openPrice, currentPrice) = result
            // openPrice 不为 null 才写 DB：盘前 openPrice=null，跳过写入，防止盘前价污染统计基准
            if (openPrice != null) {
                val dateKey = DateUtils.todayKey()
                priceDao.insertIgnore(PriceHistory(
                    dateKey = dateKey,
                    price = openPrice,
                    timestamp = System.currentTimeMillis()
                ))
            }
            return@withContext currentPrice  // 实时价用于首页显示
        }
        null
    }

    /**
     * 接口1：新浪财经 沪金连续 nf_AU0（上期所黄金期货，CNY/克）  GBK编码
     * 字段: [0]名称 [1]代码 [2]昨结算 [3]今开盘 [4]最低 [5]? [6]最新价 ...
     * 返回 Pair(开盘价?, 实时价)：盘前 fields[3]=0 时 openPrice=null，不写 DB
     */
    private fun fetchGoldPriceSina(): Pair<Double?, Double>? {
        return try {
            val req = Request.Builder()
                .url("https://hq.sinajs.cn/list=nf_AU0")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .addHeader("Referer", "https://finance.sina.com.cn/")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            val text = String(bytes, Charsets.ISO_8859_1)
            val match = Regex("\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return null
            val fields = match.split(",")
            if (fields.size < 7) return null
            val openPrice = fields[3].toDoubleOrNull() ?: 0.0   // 今开盘
            val currentPrice = fields[6].toDoubleOrNull() ?: 0.0  // 最新价
            if (currentPrice <= 100) return null
            // 盘前 openPrice=0 → null，告知上层不要写 DB
            Pair(if (openPrice > 100) openPrice else null, currentPrice)
        } catch (e: Exception) {
            Log.w("GoldRepo", "Sina nf_AU0 failed: ${e.message}")
            null
        }
    }

    /**
     * 接口2：新浪财经 黄金ETF sh518880（1份=0.01克，×100换算 CNY/克）
     * 字段: [0]名称 [1]今开盘 [2]昨收 [3]当前价 [4]最高 [5]最低 ...
     * 返回 Pair(开盘价?, 实时价)：盘前 openPrice=null
     */
    private fun fetchGoldPriceEastMoney(): Pair<Double?, Double>? {
        return try {
            val req = Request.Builder()
                .url("https://hq.sinajs.cn/list=sh518880")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .addHeader("Referer", "https://finance.sina.com.cn/")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            val text = String(bytes, Charsets.ISO_8859_1)
            val match = Regex("\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return null
            val fields = match.split(",")
            if (fields.size < 4) return null
            val openPrice = (fields[1].toDoubleOrNull() ?: 0.0) * 100.0   // 今开盘×100
            val currentPrice = (fields[3].toDoubleOrNull() ?: 0.0) * 100.0  // 当前价×100
            if (currentPrice <= 100) return null
            Pair(if (openPrice > 100) openPrice else null, currentPrice)
        } catch (e: Exception) {
            Log.w("GoldRepo", "Sina ETF 518880 failed: ${e.message}")
            null
        }
    }

    /**
     * 接口3：新浪财经 沪金近月合约（兜底，同 nf_AU0 字段格式）
     * 返回 Pair(开盘价?, 实时价)：盘前 openPrice=null
     */
    private fun fetchGoldPriceSGE(): Pair<Double?, Double>? {
        return try {
            val now = Calendar.getInstance()
            val year = now.get(Calendar.YEAR) % 100
            val month = now.get(Calendar.MONTH) + 1
            val contracts = listOf(
                "nf_Au%02d%02d".format(year, month),
                "nf_Au%02d%02d".format(if (month == 12) year + 1 else year, if (month == 12) 1 else month + 1)
            )
            for (contract in contracts) {
                val result = fetchSinaFuturesPrice(contract)
                if (result != null) return result
            }
            null
        } catch (e: Exception) {
            Log.w("GoldRepo", "Sina futures fallback failed: ${e.message}")
            null
        }
    }

    private fun fetchSinaFuturesPrice(symbol: String): Pair<Double?, Double>? {
        return try {
            val req = Request.Builder()
                .url("https://hq.sinajs.cn/list=$symbol")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .addHeader("Referer", "https://finance.sina.com.cn/")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            val text = String(bytes, Charsets.ISO_8859_1)
            val match = Regex("\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return null
            val fields = match.split(",")
            if (fields.size < 7) return null
            val openPrice = fields[3].toDoubleOrNull() ?: 0.0
            val currentPrice = fields[6].toDoubleOrNull() ?: 0.0
            if (currentPrice <= 100) return null
            Pair(if (openPrice > 100) openPrice else null, currentPrice)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getLastKnownPrice(): PriceHistory? = withContext(Dispatchers.IO) {
        priceDao.getLatestPrice()
    }

    suspend fun getRecentPrices(days: Int = 90): List<PriceHistory> = withContext(Dispatchers.IO) {
        priceDao.getRecentHistoryList(days)
    }

    fun getAllPricesLive() = priceDao.getAllPricesLive()

    // ─── 预测 ─────────────────────────────────────────────────────────────────
    suspend fun computeAndSavePrediction(): Triple<Double, Double, Double> = withContext(Dispatchers.IO) {
        val todayKey = DateUtils.todayKey()
        val tomorrowKey = DateUtils.daysFromNowKey(1)
        // 以今天的 createdDate 为唯一性标识，避免重复计算
        val existing = predictionDao.getPredictionByCreatedDate(todayKey)
        if (existing != null) {
            return@withContext Triple(existing.predictedPrice1d, existing.predictedPrice2d, existing.predictedPrice3d)
        }
        val prices = priceDao.getRecentHistoryList(90).map { it.price }.reversed()
        val result = PredictionUtils.predict(prices)
        val record = PredictionRecord(
            predictionDate = tomorrowKey,   // 被预测的那天（明天）
            createdDate = todayKey,          // 预测创建于今天
            predictedPrice1d = result.price1d,
            predictedPrice2d = result.price2d,
            predictedPrice3d = result.price3d,
            timestamp = System.currentTimeMillis()
        )
        predictionDao.insertOrUpdate(record)
        Triple(result.price1d, result.price2d, result.price3d)
    }

    suspend fun getLatestPrediction() = withContext(Dispatchers.IO) {
        predictionDao.getLatestPrediction()
    }

    suspend fun getPredictionsForDeviation(
        start: String = DateUtils.daysAgoKey(90),
        end: String = DateUtils.todayKey()
    ): List<PredictionRecord> = withContext(Dispatchers.IO) {
        predictionDao.getPredictionsSince(start)
    }

    // ─── 账户 ─────────────────────────────────────────────────────────────────
    fun getAllAccountsLive() = accountDao.getAllAccounts()

    suspend fun getAllAccounts() = withContext(Dispatchers.IO) { accountDao.getAllAccountsList() }

    suspend fun addAccount(account: Account) = withContext(Dispatchers.IO) {
        accountDao.insert(account)
    }

    suspend fun updateAccount(account: Account) = withContext(Dispatchers.IO) {
        accountDao.update(account)
    }

    suspend fun deleteAccount(account: Account) = withContext(Dispatchers.IO) {
        accountDao.delete(account)
    }

    suspend fun resetAccount(accountId: Long) = withContext(Dispatchers.IO) {
        val account = accountDao.getAllAccountsList().find { it.id == accountId } ?: return@withContext
        accountDao.update(account.copy(cashBalance = 100000.0, holdingGrams = 0.0, avgCostPrice = 0.0))
    }

    suspend fun recordTrade(trade: TradeRecordV2) = withContext(Dispatchers.IO) {
        tradeDao.insert(trade)
        val accounts = accountDao.getAllAccountsList()
        val account = accounts.find { it.id == trade.accountId } ?: return@withContext
        val updatedAccount = if (trade.action == "BUY") {
            val newGrams = account.holdingGrams + trade.grams
            val newAvg = if (newGrams > 0)
                (account.holdingGrams * account.avgCostPrice + trade.grams * trade.price) / newGrams
            else 0.0
            account.copy(
                cashBalance = account.cashBalance - trade.amount,
                holdingGrams = newGrams,
                avgCostPrice = newAvg
            )
        } else {
            account.copy(
                cashBalance = account.cashBalance + trade.amount,
                holdingGrams = maxOf(0.0, account.holdingGrams - trade.grams)
            )
        }
        accountDao.update(updatedAccount)
    }

    fun getTradesLive(accountId: Long) = tradeDao.getTradesByAccount(accountId)

    suspend fun getRecentTrades(accountId: Long, limit: Int = 10) = withContext(Dispatchers.IO) {
        tradeDao.getRecentTrades(accountId, limit)
    }

    // ─── DCA 对比计算 ──────────────────────────────────────────────────────────
    suspend fun computeDcaReturns(monthlyAmount: Double = 2000.0, months: Int = 36):
            Triple<Double, Double, Double> = withContext(Dispatchers.IO) {
        val csi300 = computeDcaReturn(csi300Dao.getAll().map { it.closePrice }, monthlyAmount, months)
        val nasdaq = computeDcaReturn(nasdaqDao.getAll().map { it.closePrice }, monthlyAmount, months)
        val gold = computeDcaReturn(priceDao.getRecentHistoryList(months * 22).map { it.price }.reversed(), monthlyAmount, months)
        Triple(csi300, nasdaq, gold)
    }

    private fun computeDcaReturn(prices: List<Double>, monthlyAmount: Double, months: Int): Double {
        if (prices.size < 2) return 0.0
        val step = maxOf(1, prices.size / months)
        var totalShares = 0.0
        var totalInvested = 0.0
        val usedPrices = (0 until months).mapNotNull { i ->
            val idx = i * step
            if (idx < prices.size) prices[idx] else null
        }
        for (price in usedPrices) {
            if (price <= 0) continue
            totalShares += monthlyAmount / price
            totalInvested += monthlyAmount
        }
        val currentPrice = prices.last()
        val currentValue = totalShares * currentPrice
        return if (totalInvested > 0) (currentValue - totalInvested) / totalInvested * 100 else 0.0
    }

    // ─── AI 策略自动交易 ───────────────────────────────────────────────────────
    suspend fun runAiStrategy(
        currentPrice: Double,
        pred1d: Double,
        buyThreshold: Double = 0.003,
        positionLimit: Double = 0.6,
        buyRatio: Double = 0.3,
        sellThreshold: Double = 0.003,
        sellRatio: Double = 0.5,
        stopLoss: Double = 0.03
    ) = withContext(Dispatchers.IO) {
        val accounts = accountDao.getAllAccountsList()
        val aiAccount = accounts.find { it.type == "AI" } ?: return@withContext
        val predictedChange = if (currentPrice > 0) (pred1d - currentPrice) / currentPrice else 0.0
        val marketValue = aiAccount.holdingGrams * currentPrice
        val totalValue = aiAccount.cashBalance + marketValue
        val positionRatio = if (totalValue > 0) marketValue / totalValue else 0.0

        // 止损：浮亏 >= stopLoss% → 清仓
        if (aiAccount.holdingGrams > 0 && aiAccount.avgCostPrice > 0) {
            val floatLoss = (currentPrice - aiAccount.avgCostPrice) / aiAccount.avgCostPrice
            if (floatLoss <= -stopLoss) {
                val sellGrams = aiAccount.holdingGrams
                val amount = sellGrams * currentPrice
                recordTrade(TradeRecordV2(accountId = aiAccount.id, dateKey = DateUtils.todayKey(),
                    action = "SELL", price = currentPrice, grams = sellGrams, amount = amount, notes = "AI止损"))
                return@withContext
            }
        }

        when {
            predictedChange >= buyThreshold && positionRatio < positionLimit -> {
                val buyAmount = aiAccount.cashBalance * buyRatio
                if (buyAmount > currentPrice * 0.1) {
                    val grams = buyAmount / currentPrice
                    recordTrade(TradeRecordV2(accountId = aiAccount.id, dateKey = DateUtils.todayKey(),
                        action = "BUY", price = currentPrice, grams = grams, amount = buyAmount, notes = "AI买入"))
                }
            }
            predictedChange <= -sellThreshold && aiAccount.holdingGrams > 0 -> {
                val sellGrams = aiAccount.holdingGrams * sellRatio
                val amount = sellGrams * currentPrice
                recordTrade(TradeRecordV2(accountId = aiAccount.id, dateKey = DateUtils.todayKey(),
                    action = "SELL", price = currentPrice, grams = sellGrams, amount = amount, notes = "AI卖出"))
            }
        }
    }

    suspend fun getHistoryForChart(days: Int = 365): List<PriceHistory> = withContext(Dispatchers.IO) {
        priceDao.getHistorySince(DateUtils.daysAgoKey(days))
    }

    suspend fun getCsi300History(): List<Csi300History> = withContext(Dispatchers.IO) {
        csi300Dao.getAll()
    }
}
