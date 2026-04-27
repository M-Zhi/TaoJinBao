package com.goldprice.app.ui.history

import androidx.lifecycle.*
import com.goldprice.app.data.model.Csi300History
import com.goldprice.app.data.model.PriceHistory
import com.goldprice.app.data.repository.GoldRepository
import com.goldprice.app.utils.DateUtils
import kotlinx.coroutines.launch

class HistoryViewModel(private val repo: GoldRepository) : ViewModel() {
    val priceHistory = repo.getAllPricesLive()

    private val _csi300History = MutableLiveData<List<Csi300History>>()
    val csi300History: LiveData<List<Csi300History>> = _csi300History

    private val _showCsi300 = MutableLiveData(false)
    val showCsi300: LiveData<Boolean> = _showCsi300

    init {
        loadCsi300()
    }

    private fun loadCsi300() {
        viewModelScope.launch {
            _csi300History.value = repo.getCsi300History()
        }
    }

    fun toggleCsi300() {
        _showCsi300.value = !(_showCsi300.value ?: false)
    }

    class Factory(private val repo: GoldRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return HistoryViewModel(repo) as T
        }
    }
}
