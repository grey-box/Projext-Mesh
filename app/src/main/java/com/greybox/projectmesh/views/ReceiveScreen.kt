package com.greybox.projectmesh.views

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.greybox.projectmesh.R
import com.greybox.projectmesh.ViewModelFactory
import com.greybox.projectmesh.model.ReceiveScreenModel
import com.greybox.projectmesh.server.AppServer
import com.greybox.projectmesh.viewModel.ReceiveScreenViewModel
import org.kodein.di.compose.localDI
import org.kodein.di.DI
import org.kodein.di.instance
import java.io.File
import java.net.URLDecoder

@Composable
fun ReceiveScreen(
    viewModel: ReceiveScreenViewModel = viewModel(
        factory = ViewModelFactory(
            di = localDI(),
            owner = LocalSavedStateRegistryOwner.current,
            vmFactory = { ReceiveScreenViewModel(it) },
            defaultArgs = null,
        )
    ),
    onAutoFinishChange: (Boolean) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState(ReceiveScreenModel())
    HandleIncomingTransfers(
        uiState = uiState,
        onAccept = viewModel::onAccept,
        onDecline = viewModel::onDecline,
        onDelete = viewModel::onDelete,
        onAutoFinishChange = onAutoFinishChange
    )
}

@Composable
fun HandleIncomingTransfers(
    uiState: ReceiveScreenModel,
    onAccept: (AppServer.IncomingTransferInfo) -> Unit = {},
    onDecline: (AppServer.IncomingTransferInfo) -> Unit = {},
    onDelete: (AppServer.IncomingTransferInfo) -> Unit = {},
    onAutoFinishChange: (Boolean) -> Unit
){
    val di: DI = localDI()
    val settingPref: SharedPreferences by di.instance(tag="settings")
    val context = LocalContext.current
    var autoFinishEnabled by remember { mutableStateOf(false) }
    val defaultUri = settingPref.getString("save_to_folder", null)
        ?: "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/Project Mesh"

    LaunchedEffect(onAutoFinishChange) {
        autoFinishEnabled = settingPref.getBoolean("auto_finish", false)
        Log.d("ReceiveScreen", "autoFinishEnabled: $autoFinishEnabled")
    }
    LaunchedEffect(autoFinishEnabled, uiState.incomingTransfers) {
        if (autoFinishEnabled) {
            uiState.incomingTransfers
                .filter { it.status == AppServer.Status.PENDING } // Only pending transfers
                .forEach { transfer ->
                    // Automatically trigger accept action
                    onAccept(transfer)
                }
        }
    }

    fun openFile(transfer: AppServer.IncomingTransferInfo){
        // get the file from the IncomingTransferInfo object
        val file = transfer.file
        // only when the file transfer completed and the file is not null
        if (file != null && transfer.status == AppServer.Status.COMPLETED){
            // Generate a content URI for the file, it provide secure access to files
            val uri = FileProvider.getUriForFile(
                context, "com.greybox.projectmesh.fileprovider", file
            )
            // allow the system to find an app capable of handling and viewing the file
            val intent = Intent(Intent.ACTION_VIEW).apply {
                // The flag is set to ensure the receiving app can temporarily access the file uri with read permission
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                // determine the MIME type based on the file's extension using MimeTypeMap
                // If the MIME type cannot be determined, a default (*/*) fallback is used
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension)?: "*/*"
                // Sets the URI and the MIME type on the intent so that the system can know
                // what kind of file it is dealing with and suggest the appropriate apps.
                setDataAndType(uri, mimeType)
            }

            // If an app is found, it launches the intent, allowing the user to view the file.
            // Otherwise, it shows a Toast message informing the user that File cannot be opened.
            if(intent.resolveActivity(context.packageManager) != null){
                try{
                    context.startActivity(intent)
                }
                catch (e: Exception){
                    Toast.makeText(context, "Error opening file: ${e.message}", Toast.LENGTH_SHORT).show()
                }

            }
            else{
                Toast.makeText(context, "File cannot be opened", Toast.LENGTH_SHORT).show()
            }
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(
            items = uiState.incomingTransfers,
            key = {Triple(it.fromHost, it.id, it.requestReceivedTime)}
        ){ transfer ->
            ListItem(
                modifier = Modifier
                    .clickable {
                        openFile(transfer)
                    }
                    .fillMaxWidth(),
                headlineContent = {
                    Text(transfer.name)
                },
                supportingContent = {
                    Column{
                        val fromHostAddress = transfer.fromHost.hostAddress
                        Text(stringResource(id = R.string.from) + " ${transfer.deviceName}(${fromHostAddress})")
                        Text(stringResource(id = R.string.status) + ": ${transfer.status}")
                        Text("${autoConvertByte(transfer.transferred)} / " +
                                "${autoConvertByte(transfer.size)}@${autoConvertMS(transfer.transferTime)}"
                        )
                        if(transfer.status == AppServer.Status.PENDING){
                            Row{
                                IconButton(onClick = {onAccept(transfer)},
                                    modifier = Modifier.width(100.dp)) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = "Accept")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(onClick = {onDecline(transfer)},
                                    modifier = Modifier.width(100.dp)) {
                                    Icon(Icons.Default.Cancel, contentDescription = "Decline")
                                }
                            }
                        }
                    }
                },
                trailingContent = {
                    if(transfer.status == AppServer.Status.COMPLETED){
                        Row (
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp) // Space between buttons
                        ){
                            IconButton(onClick = {onDelete(transfer)}) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                            IconButton(onClick = {onDownload(context, transfer, defaultUri)}) {
                                Icon(Icons.Default.Download, contentDescription = "Download")
                            }
                        }
                    }
                    else if(transfer.status == AppServer.Status.DECLINED || transfer.status == AppServer.Status.FAILED){
                        Row (
                            verticalAlignment = Alignment.CenterVertically
                        ){
                            IconButton(onClick = {onDelete(transfer)}) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            )
            HorizontalDivider()
        }
    }
}

fun onDownload(context: Context, transfer: AppServer.IncomingTransferInfo, uriOrPath: String) {
    if (uriOrPath.startsWith("content://")) {
        // Handle user-selected directory via SAF
        saveFileToContentUri(context, transfer, uriOrPath)
    } else {
        // Handle default directory using File API
        saveFileToDefaultPath(context, transfer, uriOrPath)
    }
}

private fun saveFileToDefaultPath(context: Context, transfer: AppServer.IncomingTransferInfo, folderPath: String) {
    val directoryFile = File(folderPath)

    // Ensure the directory exists
    if (!directoryFile.exists() && !directoryFile.mkdirs()) {
        Toast.makeText(context, "Failed to create directory: $folderPath", Toast.LENGTH_LONG).show()
        return
    }

    // Check if the file in `transfer` is valid
    val sourceFile = transfer.file
    if (sourceFile == null || !sourceFile.exists()) {
        Toast.makeText(context, "File not available for download", Toast.LENGTH_LONG).show()
        return
    }

    try {
        // Create the target file in the directory
        val targetFile = File(directoryFile, transfer.name ?: "unknown_file")
        sourceFile.copyTo(targetFile, overwrite = true)
        Toast.makeText(context, "File saved to: ${targetFile.absolutePath}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Error saving file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}

private fun saveFileToContentUri(context: Context, transfer: AppServer.IncomingTransferInfo, uriString: String) {
    val treeUri = Uri.parse(uriString)
    val resolver = context.contentResolver

    // Retrieve the document ID from the tree URI
    val documentId = DocumentsContract.getTreeDocumentId(treeUri)
    val directoryUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    // Check if the file in `transfer` is valid
    val sourceFile = transfer.file
    if (sourceFile == null || !sourceFile.exists()) {
        Toast.makeText(context, "File not available for download", Toast.LENGTH_LONG).show()
        return
    }

    try {
        // Create a new file in the selected directory
        val targetUri = DocumentsContract.createDocument(
            resolver,
            directoryUri,
            "application/octet-stream", // MIME type for binary files
            transfer.name ?: "unknown_file"
        )

        if (targetUri != null) {
            resolver.openOutputStream(targetUri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream) // Copy file contents
                }
            }
            // Notify the user of the successful download
            Toast.makeText(context, "File saved to: " + targetUri.path, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Error creating file in the selected directory", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Error saving file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}

