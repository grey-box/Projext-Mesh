package com.greybox.projectmesh.views

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.greybox.projectmesh.GlobalApp
import com.greybox.projectmesh.R
import com.greybox.projectmesh.ViewModelFactory
import com.greybox.projectmesh.buttonStyle.WhiteButton
import com.greybox.projectmesh.model.SendScreenModel
import com.greybox.projectmesh.viewModel.SendScreenViewModel
import org.kodein.di.compose.localDI

@Composable
fun SendScreen(
    onSwitchToSelectDestNode: (List<Uri>) -> Unit,
    viewModel: SendScreenViewModel = viewModel(
        factory = ViewModelFactory(
            di = localDI(),
            owner = LocalSavedStateRegistryOwner.current,
            vmFactory = { SendScreenViewModel(it, onSwitchToSelectDestNode) },
            defaultArgs = null,
        )
    ),
) {
    // declare the UI state
    val uiState: SendScreenModel by viewModel.uiState.collectAsState(SendScreenModel())
    // File picker launcher
    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ){ uris ->
        if (uris.isNotEmpty()){
            viewModel.onFileChosen(uris)
        }
    }
    Box(modifier = Modifier.fillMaxSize()){
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 72.dp)) {
            DisplayAllPendingTransfers(uiState)
        }
        WhiteButton(onClick = { openDocumentLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            text = stringResource(id = R.string.send_file),
            enabled = true
        )
    }
}

@Composable
// Display all the pending transfers
fun DisplayAllPendingTransfers(
    uiState: SendScreenModel,
){
    LazyColumn {
        items(
            items = uiState.outgoingTransfers,
            key = { it.id }
        ) {transfer ->
            ListItem(
                headlineContent = {
                    Text(transfer.name)
                },
                supportingContent = {
                    Column {
                        val byteTransferred: Int = transfer.transferred
                        val byteSize: Int = transfer.size
                        val toHostAddress = transfer.toHost.hostAddress
                        val deviceName = toHostAddress?.let {
                            GlobalApp.DeviceInfoManager.getDeviceName(it)
                        }
                        if (deviceName != null){
                            Text("To ${deviceName}(${toHostAddress})")
                        }
                        else{
                            Text("To Loading...(${toHostAddress})")
                        }
                        Text(stringResource(id = R.string.status) + ": ${transfer.status}")
                        Text(stringResource(id = R.string.send) + " ${autoConvertByte(byteTransferred)} / ${autoConvertByte(byteSize)}")
                    }
                }
            )
            HorizontalDivider()
        }
    }
}

fun autoConvertByte(byteSize: Int): String{
    val kb = Math.round(byteSize / 1024.0 * 100) / 100.0
    val mb = Math.round((byteSize / (1024.0 * 1024.0) * 100) / 100.0)
    if (byteSize == 0){
        return "0B"
    }
    else if (mb < 1){
        return "${kb}KB"
    }
    return "${mb}MB"
}

fun autoConvertMS(ms: Int): String {
    val second = Math.round(ms / 1000.0 * 100) / 100.0
    val minute = Math.round((second / 60.0) * 100) / 100.0
    return if (second >= 1 && minute < 1) {
        "${second}s"
    } else if (minute >= 1) {
        "${minute}m"
    } else {
        "${ms}ms"
    }
}