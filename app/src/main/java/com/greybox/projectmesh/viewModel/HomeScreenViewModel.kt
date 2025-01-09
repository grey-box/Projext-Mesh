package com.greybox.projectmesh.viewModel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greybox.projectmesh.model.HomeScreenModel
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance


class HomeScreenViewModel(di: DI): ViewModel(){
    // _uiState will be updated whenever there is a change in the UI state
    private val _uiState = MutableStateFlow(HomeScreenModel())
    // uiState is a read-only property that shows the current UI state
    val uiState: Flow<HomeScreenModel> = _uiState.asStateFlow()
    // di is used to get the AndroidVirtualNode instance
    private val node: AndroidVirtualNode by di.instance()
    init {
        // launch a coroutine
        viewModelScope.launch {
            // collect the state flow of the AndroidVirtualNode
            node.state.collect {
                // update the UI state with the new state
                _uiState.update { prev ->
                    // Creates a new instance of the state,
                    // copying the existing properties and updating only the ones specified.
                    prev.copy(
                        wifiState = it.wifiState,
                        connectUri = it.connectUri,
                        localAddress = it.address,
                        hotspotStatus = it.wifiState.hotspotIsStarted,
                        wifiConnectionsEnabled = (it.wifiState.connectConfig != null),
                        nodesOnMesh = it.originatorMessages.keys
                    )
                }
            }
        }
        if (node.meshrabiyaWifiManager.is5GhzSupported){
            _uiState.update { prev ->
                prev.copy(
                    bandMenu = listOf(ConnectBand.BAND_5GHZ, ConnectBand.BAND_2GHZ),
                    band = ConnectBand.BAND_5GHZ
                )
            }
        }
    }

    fun onConnectBandChanged(band: ConnectBand) {
        _uiState.update { prev ->
            prev.copy(
                band = band
            )
        }
    }

    fun onSetHotspotTypeToCreate(hotspotType: HotspotType) {
        _uiState.update { prev ->
            prev.copy(
                hotspotTypeToCreate = hotspotType
            )
        }
    }

    // This function is responsible for enabling or disabling the hotspot
    fun onSetIncomingConnectionsEnabled(enable: Boolean) {
        viewModelScope.launch {
            val result = node.setWifiHotspotEnabled(
                enabled = enable,
                preferredBand = _uiState.value.band,
                hotspotType = _uiState.value.hotspotTypeToCreate,
            )
        }
    }

    // This function is responsible for connecting to a wifi network as a station (Client Mode)
    fun onConnectWifi(
        hotSpotConfig: WifiConnectConfig
    ) {
        viewModelScope.launch {
            try {
                node.connectAsStation(hotSpotConfig)
            } catch (e: Exception) {
                Log.e("HomeScreenViewModel", "onConnectWifi: ${e.message}")
            }
        }
    }

    // disconnect the wifi station
    fun onClickDisconnectStation() {
        viewModelScope.launch {
            node.disconnectWifiStation()
        }
    }
}