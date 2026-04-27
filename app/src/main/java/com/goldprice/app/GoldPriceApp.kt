package com.goldprice.app

import android.app.Application
import com.goldprice.app.data.db.AppDatabase
import com.goldprice.app.data.repository.GoldRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GoldPriceApp : Application() {
    lateinit var repository: GoldRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(this)
        repository = GoldRepository(
            db.priceDao(), db.predictionDao(), db.accountDao(),
            db.tradeDao(), db.csi300Dao(), db.nasdaqDao()
        )
        // 启动时异步初始化种子数据和默认账户
        CoroutineScope(Dispatchers.IO).launch {
            repository.seedDataIfNeeded()
        }
    }
}
