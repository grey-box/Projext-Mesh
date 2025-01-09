package com.greybox.projectmesh.views

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.greybox.projectmesh.NEARBY_WIFI_PERMISSION_NAME
import com.greybox.projectmesh.R
import com.greybox.projectmesh.ViewModelFactory
import com.greybox.projectmesh.buttonStyle.WhiteButton
import com.greybox.projectmesh.hasNearbyWifiDevicesOrLocationPermission
import com.greybox.projectmesh.hasStaApConcurrency
import com.greybox.projectmesh.model.HomeScreenModel
import com.greybox.projectmesh.viewModel.HomeScreenViewModel
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.MeshrabiyaConnectLink
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.wifi.state.WifiStationState
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import android.net.wifi.WifiManager

@Composable
// We customize the viewModel since we need to inject dependencies
fun HomeScreen(
    onSwitchToSearchScreen: () -> Unit = {},
    viewModel: HomeScreenViewModel = viewModel(
    factory = ViewModelFactory(
        di = localDI(),
        owner = LocalSavedStateRegistryOwner.current,
        vmFactory = { HomeScreenViewModel(it) },
        defaultArgs = null)),
    deviceName: String?
)
{
    val di = localDI()
    val uiState: HomeScreenModel by viewModel.uiState.collectAsState(initial = HomeScreenModel())
    val node: VirtualNode by di.instance()
    val context = LocalContext.current
    // Request nearby wifi permission
    val requestNearbyWifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ){ granted -> if (granted){
        if(context.hasNearbyWifiDevicesOrLocationPermission()){
            viewModel.onSetIncomingConnectionsEnabled(true)
        }
    } }
    // Launch the home screen
    StartHomeScreen(
        uiState = uiState,
        node = node as AndroidVirtualNode,
        onSetIncomingConnectionsEnabled = {enabled ->
            if (enabled) {
                if (!context.hasNearbyWifiDevicesOrLocationPermission()) {
                    requestNearbyWifiPermissionLauncher.launch(NEARBY_WIFI_PERMISSION_NAME)
                    return@StartHomeScreen
                }
                if (uiState.hotspotTypeToCreate == HotspotType.WIFIDIRECT_GROUP) {
                    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    if (!wifiManager.isWifiEnabled) {
                        Toast.makeText(context, "Please Turn on Wifi", Toast.LENGTH_SHORT).show()
                        return@StartHomeScreen
                    }
                }
            }
            viewModel.onSetIncomingConnectionsEnabled(enabled)
        },
        onClickDisconnectWifiStation = viewModel::onClickDisconnectStation,
        deviceName = deviceName,
        onSwitchToSearchScreen = onSwitchToSearchScreen,
        onSetBand = viewModel::onConnectBandChanged,
        onSetHotspotTypeToCreate = viewModel::onSetHotspotTypeToCreate
    )
}

// Display the home screen
@Composable
fun StartHomeScreen(
    uiState: HomeScreenModel,
    node: AndroidVirtualNode,
    onSetIncomingConnectionsEnabled: (Boolean) -> Unit = { },
    onClickDisconnectWifiStation: () -> Unit = { },
    viewModel: HomeScreenViewModel = viewModel(),
    deviceName: String?,
    onSwitchToSearchScreen: () -> Unit = {},
    onSetBand: (ConnectBand) -> Unit = { },
    onSetHotspotTypeToCreate: (HotspotType) -> Unit = { },
){
    val di = localDI()
    val barcodeEncoder = remember { BarcodeEncoder() }
    val context = LocalContext.current
    var userEnteredConnectUri by rememberSaveable { mutableStateOf("") }
    // connect to other device via connect uri
    fun connect(uri: String): Unit {
        try {
            // Parse the link, get the wifi connect configuration.
            val hotSpot = MeshrabiyaConnectLink.parseUri(
                uri = uri,
                json = di.direct.instance()
            ).hotspotConfig
            // if the configuration is valid, connect to the device.
            if (hotSpot != null) {
                if(hotSpot.nodeVirtualAddr !in uiState.nodesOnMesh) {
                    // Connect device thru wifi connection
                    viewModel.onConnectWifi(hotSpot)
                }else{
                    Toast.makeText(context, "Already connected to this device", Toast.LENGTH_SHORT).show()
                    Log.d("Connection", "Already connected to this device")
                }
            } else {
                Toast.makeText(context, "Link doesn't have a connect config", Toast.LENGTH_SHORT).show()
                Log.d("Connection", "Link doesn't have a connect config")
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Invalid Link", Toast.LENGTH_SHORT).show()
            Log.e("Connection", "Invalid Link ${e.message}")
        }
    }

    // initialize the QR code scanner
    val qrScannerLauncher = rememberLauncherForActivityResult(contract = ScanContract()) { result ->
        // Get the contents of the QR code
        val link = result.contents
        if (link != null) {
            connect(link)
        }else{
            Toast.makeText(context, "QR Code scan doesn't return a link", Toast.LENGTH_SHORT).show()
            Log.d("Connection", "QR Code scan doesn't return a link")
        }
    }
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Column {
            Spacer(modifier = Modifier.height(6.dp))
            // Display the device name and IP
            LongPressCopyableText(
                context = context,
                text = "",
                textCopyable = deviceName.toString(),
                textSize = 15,
                padding = 6
            )
            Spacer(modifier = Modifier.height(6.dp))
            LongPressCopyableText(
                context = context,
                text = stringResource(id = R.string.ip_address) + ": ",
                textCopyable = uiState.localAddress.addressToDotNotation(),
                textSize = 15,
                padding = 6
            )

            Spacer(modifier = Modifier.height(12.dp))

            if(uiState.connectBandVisible) {
                Column {
                    Text(
                        modifier = Modifier.padding(horizontal = 6.dp),
                        text = stringResource(id = R.string.band),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp)
                    ) {
                        uiState.bandMenu.forEach { band ->
                            FilterChip(
                                selected = uiState.band == band,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                onClick = {
                                    onSetBand(band)
                                },
                                label = {
                                    Text(band.toString())
                                },
                            )
                        }
                    }
                }
            }
            // Function to check if Wi-Fi Direct is supported
            fun isWifiDirectSupported(context: Context): Boolean {
                return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
            }
            if(!uiState.incomingConnectionsEnabled) {
                val wifiDirectSupported = isWifiDirectSupported(context)
                Column {
                    Text(
                        modifier = Modifier.padding(horizontal = 6.dp),
                        text = stringResource(id = R.string.hotspot_type),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp)
                    ) {
                        uiState.hotspotTypeMenu.forEach { hotspotType ->
                            val isDisabled = (hotspotType == HotspotType.WIFIDIRECT_GROUP && !wifiDirectSupported)
                            FilterChip(
                                enabled = !isDisabled,
                                selected = hotspotType == uiState.hotspotTypeToCreate,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                onClick = { onSetHotspotTypeToCreate(hotspotType) },
                                label = { Text(hotspotType.toString()) }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Display the "Start Hotspot" button
            val stationState = uiState.wifiState?.wifiStationState
            if (!uiState.wifiConnectionsEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    WhiteButton(
                        onClick = { onSetIncomingConnectionsEnabled(true) },
                        modifier = Modifier.padding(4.dp),
                        text = stringResource(id = R.string.start_hotspot),
                        // If not connected to a WiFi, enable the button
                        // Else, check if the device supports WiFi STA/AP Concurrency
                        // If it does, enable the button. Otherwise, disable it
                        enabled = if(stationState == null || stationState.status == WifiStationState.Status.INACTIVE) true else context.hasStaApConcurrency()
                    )
                }
            }
            // Display the "Stop Hotspot" button
            else{
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    WhiteButton(
                        onClick = { onSetIncomingConnectionsEnabled(false) },
                        modifier = Modifier.padding(4.dp),
                        text = stringResource(id = R.string.stop_hotspot),
                        enabled = true
                    )
                }
            }

            // Generating QR CODE
            val connectUri = uiState.connectUri
            if (connectUri != null && uiState.wifiConnectionsEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                QRCodeView(
                    connectUri,
                    barcodeEncoder,
                    uiState.wifiState?.connectConfig?.ssid,
                    uiState.wifiState?.connectConfig?.passphrase,
                    uiState.wifiState?.connectConfig?.bssid,
                    uiState.wifiState?.connectConfig?.port.toString())
                // Display connectUri
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = stringResource(id = R.string.instruction_start_hotspot))
                Button(onClick = {
                    val sendIntent: Intent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, connectUri)
                        type = "text/plain"
                    }
                    val shareIntent = Intent.createChooser(sendIntent, null)
                    context.startActivity(shareIntent)
                }, modifier = Modifier.padding(4.dp), enabled = true) {
                    Text(stringResource(id = R.string.share_connect_uri))
                }
            }
            // Scan the QR CODE
            // If the stationState is not null and its status is INACTIVE,
            // It will display the option to connect via a QR code scan.
            if (stationState != null){
                if (stationState.status == WifiStationState.Status.INACTIVE){
                    Column (modifier = Modifier.fillMaxWidth()){
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(modifier = Modifier.padding(6.dp), text = stringResource(id = R.string.wifi_station_connection), style = TextStyle(fontSize = 16.sp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Row (modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center)
                        {
                            WhiteButton(onClick = {
                                qrScannerLauncher.launch(ScanOptions().setOrientationLocked(false)
                                    .setPrompt("Scan another device to join the Mesh")
                                    .setBeepEnabled(true)
                                )},
                                modifier = Modifier.padding(4.dp),
                                text = stringResource(id = R.string.connect_via_qr_code_scan),
                                // If the hotspot isn't started, enable the button
                                // Else, check if the device supports WiFi STA/AP Concurrency
                                // If it does, enable the button. Otherwise, disable it
                                enabled = if(!uiState.hotspotStatus) true else context.hasStaApConcurrency()
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(modifier = Modifier.padding(6.dp), text = stringResource(id = R.string.instruction))
                        Spacer(modifier = Modifier.height(4.dp))
                        TextField(
                            value = userEnteredConnectUri,
                            onValueChange = {
                                userEnteredConnectUri = it
                            },
                            label = { Text(stringResource(id = R.string.prompt_enter_uri)) }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        WhiteButton(
                            onClick = {
                                connect(userEnteredConnectUri)
                            },
                            modifier = Modifier.padding(4.dp),
                            text = stringResource(id = R.string.connect_via_entering_connect_uri),
                            // If the hotspot isn't started, enable the button
                            // Else, check if the device supports WiFi STA/AP Concurrency
                            // If it does, enable the button. Otherwise, disable it
                            enabled = if (!uiState.hotspotStatus) true else context.hasStaApConcurrency()
                        )
                    }
                }
                // If the stationState is not INACTIVE, it displays a ListItem that represents
                // the current connection status.
                else{
                    ListItem(
                        headlineContent = {
                            Text(stationState.config?.ssid ?: "(Unknown SSID)")
                        },
                        supportingContent = {
                            Text(
                                (stationState.config?.nodeVirtualAddr?.addressToDotNotation() ?: "") +
                                        " - ${stationState.status}"
                            )
                        },
                        leadingContent = {
                            if(stationState.status == WifiStationState.Status.CONNECTING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(8.dp)
                                )
                            }else {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = "",
                                )
                            }
                        },
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    onClickDisconnectWifiStation()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Disconnect",
                                )
                            }
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            // add a Hotspot status indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(modifier = Modifier.padding(6.dp), text = stringResource(id = R.string.hotspot_status) + ": " +
                        if (uiState.hotspotStatus) stringResource(
                            id = R.string.hotspot_status_online
                        ) else stringResource(id = R.string.hotspot_status_offline))
                Spacer(modifier = Modifier.width(8.dp)) // Adds some space between text and dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (uiState.hotspotStatus) Color.Green else Color.Red,
                            shape = CircleShape
                        )
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            WhiteButton(onClick = { onSwitchToSearchScreen() },
                text = stringResource(id = R.string.search_nearby_devices),
                modifier = Modifier.padding(4.dp),
                enabled = if(!uiState.hotspotStatus) true else context.hasStaApConcurrency()
            )
        }
    }
}

// Enable users to copy text by holding down the text for a long press
@Composable
fun LongPressCopyableText(context: Context,
                          text: String,
                          textCopyable: String,
                          textSize: Int,
                          padding: Int = 0){
    val clipboardManager = LocalClipboardManager.current
    BasicText(
        text = text + textCopyable,
        style = TextStyle(
            fontSize = textSize.sp,
            color = MaterialTheme.colorScheme.onBackground),
        modifier = Modifier.pointerInput(textCopyable) {
            detectTapGestures(
                onLongPress = {
                    clipboardManager.setText(AnnotatedString(textCopyable))
                    Toast.makeText(context, "Text copied to clipboard!", Toast.LENGTH_SHORT).show()
                })
        }.padding(padding.dp)
    )
}

// display the QR code
@Composable
fun QRCodeView(qrcodeUri: String, barcodeEncoder: BarcodeEncoder, ssid: String?, password: String?,
               mac: String?, port: String?) {
    val qrCodeBitMap = remember(qrcodeUri) {
        barcodeEncoder.encodeBitmap(
            qrcodeUri, BarcodeFormat.QR_CODE, 400, 400
        ).asImageBitmap()
    }
    Row {
        // QR Code left side, Device info on the right side
        Image(
            bitmap = qrCodeBitMap,
            contentDescription = "QR Code"
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "SSID: $ssid")
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "Password: $password")
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "MAC Address: $mac")
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "Port: $port")
        }
    }
}