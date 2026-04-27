package com.goldprice.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.goldprice.app.data.model.*

@Database(
    entities = [
        PriceHistory::class,
        PredictionRecord::class,
        TradeRecord::class,
        Portfolio::class,
        Account::class,
        Csi300History::class,
        NasdaqHistory::class,
        TradeRecordV2::class
    ],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun priceDao(): PriceDao
    abstract fun predictionDao(): PredictionDao
    abstract fun accountDao(): AccountDao
    abstract fun tradeDao(): TradeDao
    abstract fun csi300Dao(): Csi300Dao
    abstract fun nasdaqDao(): NasdaqDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "goldprice.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
