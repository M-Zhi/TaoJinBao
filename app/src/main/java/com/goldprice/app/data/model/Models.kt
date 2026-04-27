package com.goldprice.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "price_history", indices = [Index(value = ["dateKey"], unique = true)])
data class PriceHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateKey: String,       // "2024-01-15"
    val price: Double,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "prediction_record", indices = [Index(value = ["predictionDate"], unique = true)])
data class PredictionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val predictionDate: String,
    val createdDate: String,
    val predictedPrice1d: Double,
    val predictedPrice2d: Double,
    val predictedPrice3d: Double,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "trade_record")
data class TradeRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val dateKey: String,
    val action: String,  // "BUY" or "SELL"
    val price: Double,
    val grams: Double,
    val amount: Double,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "portfolio")
data class Portfolio(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val holdingGrams: Double = 0.0,
    val avgCostPrice: Double = 0.0,
    val totalInvested: Double = 0.0
)

@Entity(tableName = "account")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,           // "AI" or "MANUAL"
    val cashBalance: Double = 100000.0,
    val holdingGrams: Double = 0.0,
    val avgCostPrice: Double = 0.0,
    val color: String = "#4DABF7",  // 账户颜色
    val isDefault: Boolean = false
)

@Entity(tableName = "csi300_history")
data class Csi300History(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateKey: String,
    val closePrice: Double,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "nasdaq_history")
data class NasdaqHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateKey: String,
    val closePrice: Double,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "trade_record_v2")
data class TradeRecordV2(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val dateKey: String,
    val action: String,
    val price: Double,
    val grams: Double,
    val amount: Double,
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
