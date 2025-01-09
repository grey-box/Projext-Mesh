package com.greybox.projectmesh.server

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import com.greybox.projectmesh.GlobalApp
import com.greybox.projectmesh.db.MeshDatabase
import com.greybox.projectmesh.db.entities.Message
import com.greybox.projectmesh.GlobalApp.Companion.TAG_VIRTUAL_ADDRESS
import com.greybox.projectmesh.extension.updateItem
import com.ustadmobile.meshrabiya.ext.copyToWithProgressCallback
import com.ustadmobile.meshrabiya.util.FileSerializer
import com.ustadmobile.meshrabiya.util.InetAddressSerializer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.headersContentLength
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import com.greybox.projectmesh.extension.getUriNameAndSize
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder

/*
This File is the Server for transferring files
The Meshrabiya test app uses NanoHttpD as the server, OkHttp as the client
*/
class AppServer(
    private val appContext: Context,
    private val httpClient: OkHttpClient,   // OkHttp client for making HTTP requests
    name: String,
    port: Int = 0,  // Port for NanoHTTPD server, default is 0
    private val localVirtualAddr: InetAddress,
    private val receiveDir: File,   // Directory for receiving files
    private val json: Json,
    private val db: MeshDatabase,
    override val di: DI,
) : NanoHTTPD(port), Closeable, DIAware {

    private val logPrefix: String = "[AppServer - $name] "

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    enum class Status {
        PENDING, IN_PROGRESS, COMPLETED, FAILED, DECLINED
    }



    // Restart method to stop and start the server with an optional new IP address
    fun restart() {
        stop() // Stop the server using NanoHTTPD's built-in stop method
        Log.d("AppServer", "Server stopped successfully")
        start(SOCKET_READ_TIMEOUT, false) // Start the server using NanoHTTPD's built-in start method
        Log.d("AppServer", "Server restarted successfully on port: $localPort")
    }


    /*
    This data class contains all the information about the outgoing transfer (Sending a file)
    Why not using Serializable annotation?
    -> Considering the use case of Outgoing Transfer, we only upload a file, and sending this file.
    -> The file will be temporary stored in the local memory.
     */
    data class OutgoingTransferInfo(
        val id: Int,                          // Transfer ID
        val name: String,                     // Name of the file being sent
        val uri: Uri,                         // URI of the file to be sent
        val toHost: InetAddress,              // Destination address of the transfer
        val status: Status = Status.PENDING,  // Initial transfer status is PENDING
        val size: Int,                        // Size of the file in bytes
        val transferred: Int = 0,             // Bytes transferred so far
    )

    /*
    This data class contains all the information about the incoming transfer (Receiving a file)
    By using Serializable annotation, The IncomingTransferInfo object will be serialized to JSON file,
    this will:
    1. persist the state of transfer, even if the app is closed or crashed
    2. be necessary for sending and receiving structured data over network
    Why there are two parameters (fromHost, file) use custom serializers?
    -> These are complex types, Kotlin's serialization framework doesn't know how to serialize them.
    -> The custom serializers are from Meshrabiya library.
    */

    @Serializable
    data class IncomingTransferInfo(
        val id: Int,
        val requestReceivedTime: Long = System.currentTimeMillis(),
        @Serializable(with = InetAddressSerializer::class)
        val fromHost: InetAddress,  // Address of the sender
        val deviceName: String = "Unknown Device",
        val name: String,   // Name of the file being received
        val status: Status = Status.PENDING,
        val size: Int,  // size of the file in byte
        val transferred: Int = 0,   // Bytes transferred so far
        val transferTime: Int = 1,  // Time taken to transfer the file in ms
        @Serializable(with = FileSerializer::class)
        val file: File? = null,
    )

    // Atomic Integer is a thread-safe integer, using incrementAndGet() method to
    // generate unique transfer ID without worrying about race conditions
    private val transferIdAtomic = AtomicInteger()


    // This is a MutableStateFlow holding a list of OutgoingTransferInfo object, It used to hold
    // and manage a list of ongoing file transfers.
    private val _outgoingTransfers = MutableStateFlow(emptyList<OutgoingTransferInfo>())
    // This is the public accessible Flow, used to observe the list of ongoing file transfers
    val outgoingTransfers: Flow<List<OutgoingTransferInfo>> = _outgoingTransfers.asStateFlow()

    // similar to the previous one, it holds a list of IncomingTransferInfo object.
    private val _incomingTransfers = MutableStateFlow(emptyList<IncomingTransferInfo>())
    val incomingTransfers: Flow<List<IncomingTransferInfo>> = _incomingTransfers.asStateFlow()

    // This method (getListeningPort) is provided by the NanoHTTPD to get the port number which the
    // server is listening.
    val localPort: Int
        get() = super.getListeningPort()

    /*
    Initialization Block:
    Fetches all qualified files and combined with previous list of files, then sorted by
    requestReceivedTime in descending order.
     */
    init {
        scope.launch {
            // fetch all files in receiveDir directory that end with .rx.json extension
            val incomingFiles = receiveDir.listFiles { file, fileName: String? ->
                fileName?.endsWith(".rx.json") == true
            }?.map {
                // It deserializes the JSON file into an IncomingTransferInfo object
                json.decodeFromString(IncomingTransferInfo.serializer(), it.readText())
                // if no files match the criteria, return an empty list
            } ?: emptyList()
            /*
             updates the _incomingTransfers MutableStateFlow with the list of incoming files
             It combines the previous list with newly read files from the directory
             All the files are sorted by requestReceivedTime in descending order, which means
             the latest request will be listed first
             */
            _incomingTransfers.update { prev ->
                buildList {
                    addAll(prev)
                    addAll(incomingFiles.sortedByDescending { it.requestReceivedTime })
                }
            }
        }
    }

    /*
    This is a crucial function that implement an HTTP Server
     */
    override fun serve(session: IHTTPSession): Response {
        // Extracts the URI from the session, which is the path of the request
        val path = session.uri
        // check if the path is for download, indicating the request wants to download a file
        if(path.startsWith("/download/")) {
            // Extracts the transfer ID (Integer)from the path by taking the last part of the path
            val xferId = path.substringAfterLast("/").toInt()
            // Find the outgoing transfer with the given xferId
            val outgoingXfer = _outgoingTransfers.value.first {
                it.id == xferId
            }
            // This tracks how many bytes have been read from the stream
            val contentIn = appContext.contentResolver.openInputStream(outgoingXfer.uri)?.let {
                InputStreamCounter(it.buffered())
            }

            // If the input stream could not be opened, the function returns an HTTP response
            // with a status of INTERNAL_ERROR and an error message
            if(contentIn == null) {
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "text/plain",
                    "Failed to open InputStream")
            }
            // Preparing the file for download
            val response = newFixedLengthResponse(
                Response.Status.OK, "application/octet-stream",
                contentIn,
                outgoingXfer.size.toLong()
            )

            //Provide status updates by checking how many bytes have been read periodically
            /*
            The loop runs until the stream (contentIn) is closed. Every 500 milliseconds,
            the code updates the _outgoingTransfers list by checking how many bytes have been read
            so far (contentIn.bytesRead).
            (.updateItem): This is a custom extension function from List
             */
            scope.launch {
                while(!contentIn.closed) {
                    _outgoingTransfers.update{ prev ->
                        prev.updateItem(
                            condition = { it.id == xferId },
                            function = { item ->
                                item.copy(
                                    transferred = contentIn.bytesRead,
                                )
                            }
                        )
                    }
                    delay(500)
                }

                /*
                After the file transfer completes, it checks if the number of bytes read equals
                the file size. If they match, the status is set to COMPLETED,
                Otherwise, it's set to FAILED.
                 */
                val status = if(contentIn.bytesRead == outgoingXfer.size) {
                    Status.COMPLETED
                }else {
                    Status.FAILED
                }

                /*
                 Updating _outgoingTransfers again to set the final transferred bytes and status
                 (COMPLETED or FAILED).
                 */
                _outgoingTransfers.update { prev ->
                    prev.updateItem(
                        condition = { it.id == xferId },
                        function = { item ->
                            item.copy(
                                transferred = contentIn.bytesRead,
                                status = status
                            )
                        }
                    )
                }
            }
            // Returns the prepared response to initiate the file download.
            return response
        }
        // Check if it is a sending request
        else if(path.startsWith("/send")) {
            // Parse the query parameters from the URL, converting them to a key-value map
            val searchParams = session.queryParameterString.split("&")
                .map {
                    it.substringBefore("=") to it.substringAfter("=")
                }.toMap()

            // Extract the values for "id", "filename", "size", and "from" from the query parameters
            val id = searchParams["id"]
            val filename = URLDecoder.decode(searchParams["filename"], "UTF-8")
            // if size is missing or invalid, then defaults to -1
            val size = searchParams["size"]?.toInt() ?: -1
            val fromAddr = searchParams["from"]
            val devicename = fromAddr?.let { GlobalApp.DeviceInfoManager.getDeviceName(it) }

            // if everything is ready, create a new incomingTransferInfo object that contains all the
            // info extract from the query parameters
            if(id != null && filename != null && fromAddr != null) {
                val incomingTransfer = IncomingTransferInfo(
                    id = id.toInt(),
                    fromHost = InetAddress.getByName(fromAddr),
                    name = filename,
                    size = size,
                    deviceName = devicename ?: ""
                )
                /*
                 update the _incomingTransfers list with the new incoming transfer
                 The new list is added to the top of the list, then existing list are
                 appended after the new one.
                 */
                _incomingTransfers.update { prev ->
                    buildList {
                        add(incomingTransfer)
                        addAll(prev)
                    }
                }
                // Return "OK", Confirming the transfer request has been handled
                return newFixedLengthResponse("OK")
            }else {
                // Return an error response if any of the required parameters are missing
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad request")
            }
        }
        // Handle decline request
        else if(path.startsWith("/decline")){
            // get the transfer id from the last part of path
            // For example: path is "/decline/123", then we get 123 and converts to int
            val xferId = path.substringAfterLast("/").toInt()
            // Update _outgoingTransfers list, setting the specific item status to DECLINED
            _outgoingTransfers.update { prev ->
                prev.updateItem(
                    condition = { it.id == xferId },
                    function = {
                        it.copy(
                            status = Status.DECLINED
                        )
                    }
                )
            }
            // return "OK", indicating the decline request has been handled
            return newFixedLengthResponse("OK")
        }
        else if(path.startsWith("/getDeviceName")){
            Log.d("AppServer", "local ip address: ${localVirtualAddr.hostAddress}")
            val settingPref: SharedPreferences by di.instance(tag="settings")
            return newFixedLengthResponse(settingPref.getString("device_name", Build.MODEL) ?: Build.MODEL)
        }
        else if(path.startsWith("/chat")) {
            val chatMessage = session.parameters["chatMessage"]?.first() ?: "Error! No message found."
            val time = session.parameters["time"]?.first()?.toLong() ?: 0
            val senderIp = InetAddress.getByName(session.parameters["senderIp"]?.first() ?: "0.0.0.0")
            val sender: String =  GlobalApp.DeviceInfoManager.getDeviceName(senderIp) ?: "Unknown"
            val chatName: String = GlobalApp.DeviceInfoManager.getChatName(senderIp)
            Log.d("Appserver", "chatMessage: $chatMessage, time: $time, sender: $sender, chatName: $chatName")
            scope.launch {
                db.messageDao().addMessage(Message(0, time, chatMessage, sender, chatName))
            }
            return newFixedLengthResponse("OK")
        }
        else {
            // Returns a NOT_FOUND response indicating that the requested path could not be found.
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "not found: $path"
            )
        }
    }

    fun sendDeviceName(wifiAddress: InetAddress, port: Int = 4242) {
        scope.launch {
            try {
                Log.d("AppServer", "wifiAddress: $wifiAddress")
                // Send local device's name to the remote device
                val uri = "http://${wifiAddress.hostAddress}:$port/getDeviceName"
                Log.d("AppServer", "URI: $uri")
                val request = Request.Builder().url(uri).build()
                Log.d("AppServer", "Request: $request")
                val response = httpClient.newCall(request).execute()
                Log.d("AppServer", "Response: $response")
                // Get the remote device's name
                val remoteDeviceName = response.body?.string()
                Log.d("AppServer", "Remote device name: $remoteDeviceName")

                if (remoteDeviceName != null) {
                    wifiAddress.hostAddress?.let { GlobalApp.DeviceInfoManager.addDevice(it, remoteDeviceName) }
                }
            }
            catch (e: Exception) {
                e.printStackTrace()
                Log.e("AppServer", "Failed to get device name from " +
                        wifiAddress.hostAddress
                )
            }
        }
    }

    /**
     * Add an outgoing transfer. This is done using a Uri so that we don't have to make our own
     * copy of the file the user wants to transfer.
     */
    fun addOutgoingTransfer(
        uri: Uri,   // The uri of the file to be transferred
        toNode: InetAddress,    // The recipient's IP address
        toPort: Int = DEFAULT_PORT, // The recipient's port number
    ): OutgoingTransferInfo {
        // generate a unique transfer ID
        val transferId = transferIdAtomic.incrementAndGet()
        // Get the name and size of the file or content using
        // customized extension function of ContentResolver
        val nameAndSize = appContext.contentResolver.getUriNameAndSize(uri)
        val validName = nameAndSize.name ?: "unknown"
        Log.d("AppServer", "$logPrefix adding outgoing transfer of $uri " +
                "(name=${nameAndSize.name} size=${nameAndSize.size} to $toNode:$toPort")

        // create an OutgoingTransferInfo object with all transfer information
        val outgoingTransfer = OutgoingTransferInfo(
            id = transferId,
            name = validName,
            uri = uri ,
            toHost = toNode,
            size = nameAndSize.size.toInt(),
        )
        // Build the request to tell the other side about the transfer
        val request = Request.Builder().url("http://${toNode.hostAddress}:$toPort/" +
                "send?id=$transferId&filename=${URLEncoder.encode(validName, "UTF-8")}" +
                "&size=${nameAndSize.size}&from=${localVirtualAddr.hostAddress}")
//            .addHeader("connection", "close")
            .build()
        Log.d("Appserver", "$logPrefix notifying $toNode of incoming transfer")
        Log.d("AppServer", "request: $request")

        // Send the request to the other side using OkHttp3
        val response = httpClient.newCall(request).execute()
        val serverResponse = response.body?.string()
        Log.d("AppServer", "$logPrefix - received response: $serverResponse")
        /*
         Update the _outgoingTransfers list with the new transfer
         Add the new transfer to the beginning of the list, then append the existing list
         */
        _outgoingTransfers.update { prev ->
            buildList {
                add(outgoingTransfer)
                addAll(prev)
            }
        }
        return outgoingTransfer
    }

    /*
     Accept an incoming transfer
     */
    fun acceptIncomingTransfer(
        transfer: IncomingTransferInfo,
        destFile: File,
        fromPort: Int = DEFAULT_PORT,
    ) {
        // record the current time
        val startTime = System.currentTimeMillis()
        // Update the _incomingTransfers list, setting the status to IN_PROGRESS
        _incomingTransfers.update { prev ->
            prev.updateItem(
                condition = { it.id == transfer.id },
                function = { item -> item.copy(
                    status = Status.IN_PROGRESS,
                )
                }
            )
        }
        try {
            // Build the request to download the file from the sender
            val request = Request.Builder()
                .url("http://${transfer.fromHost.hostAddress}:$fromPort/download/${transfer.id}")
                .build()
            // sending the request using OkHttp3
            val response = httpClient.newCall(request).execute()
            // Get the size of the file that will be downloaded from response
            val fileSize = response.headersContentLength()
            // 0L -> L indicates type Long
            var lastUpdateTime = 0L
            /*
            Download file,Writes it to destFile, and reports progress every 500 ms
             */
            val totalTransferred = response.body?.byteStream()?.use { responseIn ->
                FileOutputStream(destFile).use { fileOut ->
                    responseIn.copyToWithProgressCallback(
                        out = fileOut,
                        onProgress = { bytesTransferred ->
                            val timeNow = System.currentTimeMillis()
                            if(timeNow - lastUpdateTime > 500) {
                                _incomingTransfers.update { prev ->
                                    prev.updateItem(
                                        condition = { it.id == transfer.id },
                                        function = { item ->
                                            item.copy(
                                                transferred = bytesTransferred.toInt()
                                            )
                                        }
                                    )
                                }
                                lastUpdateTime = System.currentTimeMillis()
                            }
                        }
                    )
                }
            }
            response.close()
            // calculate the total time taken for downloading the file
            val transferDurationMs = (System.currentTimeMillis() - startTime).toInt()
            // Update the status of the transfer based on whether the entire file was
            // successfully downloaded or not
            val incomingTransfersVal = _incomingTransfers.updateAndGet { prev ->
                prev.updateItem(
                    condition = { it.id == transfer.id },
                    function = { item ->
                        item.copy(
                            transferTime = transferDurationMs,
                            status = if(totalTransferred == fileSize) {
                                Status.COMPLETED
                            }else {
                                Status.FAILED
                            },
                            file = destFile,
                            transferred = totalTransferred?.toInt() ?: item.transferred
                        )
                    }
                )
            }

            //Write JSON to file so received files can be listed after app restarts etc.
            val incomingTransfer = incomingTransfersVal.firstOrNull {
                it.id == transfer.id
            }
            // Create a JSON file and write the serialized transfer info to the file
            if(incomingTransfer != null) {
                val jsonFile = File(receiveDir, "${incomingTransfer.name}.rx.json")
                jsonFile.writeText(json.encodeToString(IncomingTransferInfo.serializer(), incomingTransfer))
            }
            val speedKBS = transfer.size / transferDurationMs
        }
        catch(e: Exception) {
            _incomingTransfers.update { prev ->
                prev.updateItem(
                    condition = { it.id == transfer.id },
                    function = { item -> item.copy(
                        transferred = destFile.length().toInt(),
                        status = Status.FAILED,
                    )
                    }
                )
            }
        }
    }

    /*
    Decline an incoming transfer
    It sends an HTTP request to notify the sender that the file transfer has been
    declined and updates the status of the transfer to DECLINED in the local list
    of incoming transfers.
     */
    suspend fun onDeclineIncomingTransfer(
        transfer: IncomingTransferInfo,
        fromPort: Int = DEFAULT_PORT,
    ) {
        /*
         This block ensures that the network request is performed on the IO thread pool,
         which is optimized for input/output operations like networking and file handling.
         */
        withContext(Dispatchers.IO) {
            // Construct an HTTP request to decline the incoming transfer(Using OkHTTP3)
            val request = Request.Builder()
                .url("http://${transfer.fromHost.hostAddress}:$fromPort/decline/${transfer.id}")
                .build()
            try {
                // Send the request to the sender and get the response
                val response = httpClient.newCall(request).execute()
                val strResponse = response.body?.string()
            }catch(_: Exception) { }
        }
        // update the _incomingTransfers list, setting the status to DECLINED
        _incomingTransfers.update { prev ->
            prev.updateItem(
                condition = { it.id == transfer.id },
                function = {
                    it.copy(
                        status = Status.DECLINED,
                    )
                }
            )
        }
    }

    /*
    Delete an incoming transfer
    It removes both file associated with the transfer and the metadata (stored in a JSON file)
    from the device, then updates the internal state to remove the transfer from the list of
    incoming transfers.
     */
    suspend fun onDeleteIncomingTransfer(
        incomingTransfer: IncomingTransferInfo
    ) {
        /*
        Since file operations can be slow and block the main thread,
        it is important to run them on the IO thread pool,
        which is optimized for disk and network I/O tasks.
         */
        withContext(Dispatchers.IO) {
            // It will create a File object pointing to the .rx.json file
            // that stores metadata about the transfer.
            val jsonFile = incomingTransfer.file?.let {
                File(it.parentFile, it.name + ".rx.json")
            }
            // Delete both the file and the JSON file associated with the transfer
            incomingTransfer.file?.delete()
            jsonFile?.delete()
            // Update the _incomingTransfers list, removing the transfer that was just deleted
            _incomingTransfers.update { prev ->
                prev.filter { it.id != incomingTransfer.id }
            }
        }
    }

    fun sendChatMessage(address: InetAddress, time: Long, message: String) {
        scope.launch {
            try {
                Log.d("AppServer", "chat message: $message")
                val httpUrl = HttpUrl.Builder()
                    .scheme("http")
                    .host(address.hostAddress)
                    .port(DEFAULT_PORT)
                    .addPathSegment("chat")
                    .addQueryParameter("chatMessage", message)
                    .addQueryParameter("time", time.toString())
                    .addQueryParameter("senderIp", localVirtualAddr.hostAddress)
                    .build()
                Log.d("Appserver", "HTTP URL: $httpUrl")
                val request = Request.Builder()
                    .url(httpUrl)
                    .build()
                Log.d("AppServer", "Request: $request")
                val response = httpClient.newCall(request).execute()
                Log.d("AppServer", "Response: $response")
            }
            catch (e: Exception) {
                e.printStackTrace()
                Log.d("AppServer", "Failed to send message to ${address.hostAddress}")
            }
        }
    }

    // Stop the server and cancel any coroutine that are running within the CoroutineScope
    override fun close() {
        stop()
        scope.cancel()
    }

    companion object {
        const val DEFAULT_PORT = 4242
        val CHAT_TYPE_PLAINTEXT = "text/plain; charset=utf-8".toMediaType()
    }
}