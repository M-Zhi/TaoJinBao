package com.goldprice.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.goldprice.app.data.model.*

@Dao
interface PriceDao {
    @Query("SELECT * FROM price_history ORDER BY dateKey DESC LIMIT :limit")
    suspend fun getRecentHistory(limit: Int): List<PriceHistory>

    @Query("SELECT * FROM price_history ORDER BY dateKey DESC LIMIT :limit")
    suspend fun getRecentHistoryList(limit: Int): List<PriceHistory>

    @Query("SELECT * FROM price_history ORDER BY dateKey DESC LIMIT 1")
    suspend fun getLatestPrice(): PriceHistory?

    @Query("SELECT * FROM price_history WHERE dateKey >= :startDate ORDER BY dateKey ASC")
    suspend fun getHistorySince(startDate: String): List<PriceHistory>

    @Query("SELECT * FROM price_history ORDER BY dateKey DESC")
    fun getAllPricesLive(): LiveData<List<PriceHistory>>

    @Query("SELECT COUNT(*) FROM price_history WHERE dateKey < :date")
    suspend fun countBefore(date: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(price: PriceHistory)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(prices: List<PriceHistory>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(price: PriceHistory): Long

    @Query("DELETE FROM price_history WHERE price <= 0")
    suspend fun deleteInvalidPrices()
}

@Dao
interface PredictionDao {
    @Query("SELECT * FROM prediction_record ORDER BY predictionDate DESC LIMIT 1")
    suspend fun getLatestPrediction(): PredictionRecord?

    @Query("SELECT * FROM prediction_record WHERE predictionDate = :date LIMIT 1")
    suspend fun getPredictionByDate(date: String): PredictionRecord?

    @Query("SELECT * FROM prediction_record WHERE createdDate = :date LIMIT 1")
    suspend fun getPredictionByCreatedDate(date: String): PredictionRecord?

    @Query("SELECT * FROM prediction_record WHERE predictionDate >= :startDate ORDER BY predictionDate ASC")
    suspend fun getPredictionsSince(startDate: String): List<PredictionRecord>

    @Query("SELECT * FROM prediction_record ORDER BY predictionDate DESC LIMIT :limit")
    suspend fun getRecentPredictions(limit: Int): List<PredictionRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: PredictionRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(record: PredictionRecord)
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM account ORDER BY id ASC")
    fun getAllAccounts(): LiveData<List<Account>>

    @Query("SELECT * FROM account ORDER BY id ASC")
    suspend fun getAllAccountsList(): List<Account>

    @Query("SELECT COUNT(*) FROM account")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: Account): Long

    @Update
    suspend fun update(account: Account)

    @Delete
    suspend fun delete(account: Account)

    @Query("DELETE FROM account WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface TradeDao {
    @Query("SELECT * FROM trade_record_v2 WHERE accountId = :accountId ORDER BY timestamp DESC")
    fun getTradesByAccount(accountId: Long): LiveData<List<TradeRecordV2>>

    @Query("SELECT * FROM trade_record_v2 WHERE accountId = :accountId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTrades(accountId: Long, limit: Int): List<TradeRecordV2>

    @Query("SELECT * FROM trade_record_v2 WHERE accountId = :accountId ORDER BY timestamp DESC")
    suspend fun getTradesByAccountList(accountId: Long): List<TradeRecordV2>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trade: TradeRecordV2): Long
}

@Dao
interface Csi300Dao {
    @Query("SELECT * FROM csi300_history ORDER BY dateKey ASC")
    suspend fun getAll(): List<Csi300History>

    @Query("SELECT * FROM csi300_history WHERE dateKey >= :startDate ORDER BY dateKey ASC")
    suspend fun getSince(startDate: String): List<Csi300History>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<Csi300History>)

    @Query("SELECT COUNT(*) FROM csi300_history")
    suspend fun count(): Int
}

@Dao
interface NasdaqDao {
    @Query("SELECT * FROM nasdaq_history ORDER BY dateKey ASC")
    suspend fun getAll(): List<NasdaqHistory>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<NasdaqHistory>)

    @Query("SELECT COUNT(*) FROM nasdaq_history")
    suspend fun count(): Int
}
