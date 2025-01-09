package com.greybox.projectmesh.model

import android.os.Build
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState

data class HomeScreenModel(
    val wifiState: MeshrabiyaWifiState? = null,
    val connectUri: String? = null,
    val localAddress: Int = 0,
    val bandMenu: List<ConnectBand> = listOf(ConnectBand.BAND_2GHZ),
    val band: ConnectBand = bandMenu.first(),
    val hotspotTypeMenu: List<HotspotType> = listOf(HotspotType.AUTO,
        HotspotType.WIFIDIRECT_GROUP,
        HotspotType.LOCALONLY_HOTSPOT),
    val hotspotTypeToCreate: HotspotType = hotspotTypeMenu.first(),
    val hotspotStatus: Boolean = false,
    val wifiConnectionsEnabled: Boolean = false,
    var isWifiConnected: Boolean = false,
    val nodesOnMesh: Set<Int> = emptySet(),
){
    val incomingConnectionsEnabled: Boolean
        get() = wifiState?.connectConfig != null

    val connectBandVisible: Boolean
        get() = Build.VERSION.SDK_INT >= 29 && wifiState?.connectConfig == null
}
