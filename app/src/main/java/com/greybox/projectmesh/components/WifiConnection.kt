//package com.greybox.projectmesh.components
//
//import android.app.Activity
//import android.companion.AssociationRequest
//import android.companion.CompanionDeviceManager
//import android.companion.WifiDeviceFilter
//import android.content.Context
//import android.content.IntentSender
//import android.net.wifi.ScanResult
//import android.net.wifi.WifiManager
//import androidx.activity.compose.rememberLauncherForActivityResult
//import androidx.activity.result.IntentSenderRequest
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.LaunchedEffect
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.remember
//import androidx.compose.runtime.setValue
//import androidx.compose.ui.platform.LocalContext
//import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
//import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
//import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
//import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectException
//import java.util.regex.Pattern
//import com.greybox.projectmesh.NEARBY_WIFI_PERMISSION_NAME
//
//
//// This File is to pre-check the wifi connection, reusing from Meshrabiya test app
///*
//Based on the Meshrabiya introduction, we can directly use the connect link to connect from another node:
//
//val connectLink = ... //Get this from QR code scan etc.
//val connectConfig = MeshrabiyaConnectLink.parseUri(connectLink).hotspotConfig
//if(connectConfig != null) {
//  myNode.connectAsStation(connectConfig)
//}
//
//WorkFlow:
//1. When the user tries to connect to a Wi-Fi network, it will first check if permission to access
//   nearby Wi-Fi devices is granted.
//2. If permission is granted, it will check if it already knows the network's BSSID
//   (unique identifier of the Wi-Fi access point).
//3. If the BSSID is known, it will directly attempt the connection. If not,
//   it uses CompanionDeviceManager to find and associate with the network.
//4. It handles the result (successful connection or error) and updates the UI as needed.
// */
//
//fun interface ConnectWifiLauncher{
//    fun launch(config: WifiConnectConfig)
//}
//
//data class ConnectRequest(
//    val receivedTime: Long = 0,
//    val connectConfig: WifiConnectConfig,
//)
//
//data class ConnectWifiLauncherResult(
//    val hotspotConfig: WifiConnectConfig?,
//    val exception: Exception? = null,
//    val isWifiConnected: Boolean = false,
//)
//enum class ConnectWifiLauncherStatus {
//    INACTIVE, REQUESTING_PERMISSION, LOOKING_FOR_NETWORK, REQUESTING_LINK,
//}
//
//@Composable
//fun meshrabiyaConnectLauncher(
//    node: AndroidVirtualNode,
//    onStatusChange: ((ConnectWifiLauncherStatus) -> Unit)?= null,
//    onResult: (ConnectWifiLauncherResult) -> Unit,
//): ConnectWifiLauncher {
//    val context = LocalContext.current
//    val wifiManager: WifiManager = remember{
//        context.getSystemService(WifiManager::class.java)
//    }
//    var pendingPermissionRequest: ConnectRequest? by remember { mutableStateOf(null) }
//    var pendingAssociationRequest: ConnectRequest? by remember { mutableStateOf(null) }
//    val intentSenderLauncher = rememberLauncherForActivityResult(
//        contract = ActivityResultContracts.StartIntentSenderForResult()
//    ) { result ->
//        val deviceManager: CompanionDeviceManager =
//            context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
//        val associations = deviceManager.associations
//        // logger?.invoke(Log.INFO, "associations = ${associations.joinToString()}")
//        val request = pendingAssociationRequest ?: return@rememberLauncherForActivityResult
//        pendingAssociationRequest = null
//
//        onStatusChange?.invoke(ConnectWifiLauncherStatus.INACTIVE)
//        if (result.resultCode == Activity.RESULT_OK) {
//            val scanResult: ScanResult? = result.data?.getParcelableExtra(
//                CompanionDeviceManager.EXTRA_DEVICE
//            )
//
//            //logger?.invoke(Log.INFO, "rememberConnectWifiLauncher: Got scan result: bssid = ${scanResult?.BSSID}")
//            node.storeBssid(request.connectConfig.ssid, scanResult?.BSSID)
//
//            onResult(
//                ConnectWifiLauncherResult(
//                    hotspotConfig = request.connectConfig.copy(
//                        bssid = scanResult?.BSSID
//                    ),
//                    isWifiConnected = true,
//                )
//            )
//        } else {
//            onResult(
//                ConnectWifiLauncherResult(
//                    hotspotConfig = null,
//                    exception = WifiConnectException("CompanionDeviceManager: device not found / not selected"),
//                    isWifiConnected = false,
//                )
//            )
//        }
//    }
//    val requestPermissionLauncher = rememberLauncherForActivityResult(
//        contract = ActivityResultContracts.RequestPermission()
//    ) { granted ->
//        val connectRequestVal = pendingPermissionRequest ?: return@rememberLauncherForActivityResult
//        pendingPermissionRequest = null
//        if(granted) {
//            // logger?.invoke(Log.DEBUG, "ConnectWifiLauncher: permission granted")
//            pendingAssociationRequest = connectRequestVal
//        }else {
//            onResult(
//                ConnectWifiLauncherResult(
//                    hotspotConfig = null,
//                    exception = WifiConnectException("Permission denied: permission not granted"),
//                    isWifiConnected = false,
//                )
//            )
//        }
//    }
//
//    LaunchedEffect(pendingAssociationRequest) {
//        val connectRequestVal = pendingAssociationRequest ?: return@LaunchedEffect
//        val ssid = connectRequestVal.connectConfig.ssid
//        val macAddr = connectRequestVal.connectConfig.linkLocalToMacAddress
//
//        val knownBssid: String? = connectRequestVal.connectConfig.bssid ?: macAddr?.toString()
//        ?: node.lookupStoredBssid(connectRequestVal.connectConfig.ssid)
//
//        val deviceManager : CompanionDeviceManager =
//            context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
//
//        if(knownBssid != null) {
//            // logger?.invoke(Log.DEBUG, "ConnectWifiLauncher: already associated with $ssid (bssid=$knownBssid)")
//            onStatusChange?.invoke(ConnectWifiLauncherStatus.INACTIVE)
//            onResult(
//                ConnectWifiLauncherResult(
//                    hotspotConfig = connectRequestVal.connectConfig.copy(
//                        bssid = knownBssid
//                    ),
//                    isWifiConnected = true,
//                )
//            )
//        }else {
//            // logger?.invoke(Log.DEBUG,
//                // "ConnectWifiLauncher: requesting association for $ssid"
//            // )
//            onStatusChange?.invoke(ConnectWifiLauncherStatus.LOOKING_FOR_NETWORK)
//            val deviceFilter = WifiDeviceFilter.Builder()
//                .setNamePattern(Pattern.compile(Pattern.quote(connectRequestVal.connectConfig.ssid)))
//                .build()
//
//            val associationRequest: AssociationRequest = AssociationRequest.Builder()
//                .addDeviceFilter(deviceFilter)
//                .setSingleDevice(true)
//                .build()
//
//
//            deviceManager.associate(
//                associationRequest,
//                object: CompanionDeviceManager.Callback() {
//                    @Deprecated("Deprecated in Java")
//                    override fun onDeviceFound(intentSender: IntentSender) {
//                        // logger?.invoke(Log.DEBUG, "ConnectWifiLauncher: onDeviceFound for $ssid")
//                        onStatusChange?.invoke(ConnectWifiLauncherStatus.REQUESTING_LINK)
//                        intentSenderLauncher.launch(
//                            IntentSenderRequest.Builder(intentSender).build()
//                        )
//                    }
//
//                    override fun onFailure(reason: CharSequence?) {
//                        // logger?.invoke(Log.DEBUG, "ConnectWifiLauncher: onFailure for $ssid - $reason")
//                        onStatusChange?.invoke(ConnectWifiLauncherStatus.INACTIVE)
//                        pendingAssociationRequest = null
//                        onResult(
//                            ConnectWifiLauncherResult(
//                                hotspotConfig = null,
//                                exception = WifiConnectException("CompanionDeviceManager: onFailure: $reason"),
//                                isWifiConnected = false,
//                            )
//                        )
//                    }
//                },
//                null
//            )
//        }
//    }
//
//    LaunchedEffect(pendingPermissionRequest) {
//        if(pendingPermissionRequest == null)
//            return@LaunchedEffect
//
//        onStatusChange?.invoke(ConnectWifiLauncherStatus.REQUESTING_PERMISSION)
//        requestPermissionLauncher.launch(NEARBY_WIFI_PERMISSION_NAME)
//    }
//
//
//    return ConnectWifiLauncher {
//        if(it.band == ConnectBand.BAND_5GHZ && !wifiManager.is5GHzBandSupported) {
//            //we cannot connect to this
//            onResult(
//                ConnectWifiLauncherResult(
//                    hotspotConfig = null,
//                    exception = WifiConnectException("5Ghz not supported: ${it.ssid} is a 5Ghz network"),
//                    isWifiConnected = false,
//                )
//            )
//        }else {
//            //Note: If the permission is already granted, requestPermission can call back immediately
//            // synchronously to the launcher's onResult. This would cause a problem because the mutable
//            // state wouldn't be updated until the next function invocation.
//            pendingAssociationRequest = null
//            pendingPermissionRequest = ConnectRequest(
//                receivedTime = System.currentTimeMillis(),
//                connectConfig = it,
//            )
//        }
//    }
//}