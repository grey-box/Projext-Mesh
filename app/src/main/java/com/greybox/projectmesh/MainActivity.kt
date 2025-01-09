package com.greybox.projectmesh

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.greybox.projectmesh.debug.CrashHandler
import com.greybox.projectmesh.debug.CrashScreenActivity
import com.greybox.projectmesh.navigation.BottomNavItem
import com.greybox.projectmesh.navigation.BottomNavigationBar
import com.greybox.projectmesh.server.AppServer
import com.greybox.projectmesh.ui.theme.AppTheme
import com.greybox.projectmesh.ui.theme.ProjectMeshTheme
import com.greybox.projectmesh.viewModel.SharedUriViewModel
import com.greybox.projectmesh.views.ChatScreen
import com.greybox.projectmesh.views.HomeScreen
import com.greybox.projectmesh.views.SettingsScreen
import com.greybox.projectmesh.views.NetworkScreen
import com.greybox.projectmesh.views.PingScreen
import com.greybox.projectmesh.views.ReceiveScreen
import com.greybox.projectmesh.views.SearchScreen
import com.greybox.projectmesh.views.SelectDestNodeScreen
import com.greybox.projectmesh.views.SendScreen
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.compose.withDI
import org.kodein.di.instance
import java.io.File
import java.util.Locale
import java.net.InetAddress

class MainActivity : ComponentActivity(), DIAware {
    override val di by closestDI()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingPref: SharedPreferences by di.instance(tag="settings")
        val appServer: AppServer by di.instance()
        setContent {
            // check if the default directory exist (Download/Project Mesh)
            val defaultDirectory = File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS),
                "Project Mesh"
            )
            if (!defaultDirectory.exists()) {
                // Create the directory if it doesn't exist
                if (defaultDirectory.mkdirs()) {
                    Log.d("DirectoryCheck", "Default directory created: ${defaultDirectory.absolutePath}")
                }
                else {
                    Log.e("DirectoryCheck", "Failed to create default directory: ${defaultDirectory.absolutePath}")
                }
            }
            else {
                Log.d("DirectoryCheck", "Default directory already exists: ${defaultDirectory.absolutePath}")
            }
            var appTheme by remember {
                mutableStateOf(AppTheme.valueOf(
                    settingPref.getString("app_theme", AppTheme.SYSTEM.name) ?:
                    AppTheme.SYSTEM.name))
            }
            var languageCode by remember {
                mutableStateOf(settingPref.getString(
                    "language", "System") ?: "System")
            }
            var restartServerKey by remember {mutableStateOf(0)}
            var deviceName by remember {
                mutableStateOf(settingPref.getString("device_name", Build.MODEL) ?: Build.MODEL)
            }

            var autoFinish by remember {
                mutableStateOf(settingPref.getBoolean("auto_finish", false))
            }

            var saveToFolder by remember {
                mutableStateOf(
                    settingPref.getString("save_to_folder", null)
                        ?: "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/Project Mesh"
                )
            }

            // State to trigger recomposition when locale changes
            var localeState by rememberSaveable { mutableStateOf(Locale.getDefault()) }

            // Remember the current screen across recompositions
            var currentScreen by rememberSaveable { mutableStateOf(BottomNavItem.Home.route) }
            LaunchedEffect(restartServerKey) {
                if (restartServerKey > 0){
                    appServer.restart()
                    Toast.makeText(this@MainActivity, "Server restart complete", Toast.LENGTH_SHORT).show()
                }
            }
            // Observe language changes and apply locale
            LaunchedEffect(languageCode) {
                localeState = updateLocale(languageCode)
            }
            key(localeState) {
                ProjectMeshTheme(appTheme = appTheme) {
                    BottomNavApp(
                        di,
                        startDestination = currentScreen,
                        onThemeChange = { selectedTheme -> appTheme = selectedTheme},
                        onLanguageChange = { selectedLanguage ->  languageCode = selectedLanguage},
                        onNavigateToScreen = {screen ->
                            currentScreen = screen },
                        onRestartServer = {restartServerKey++},
                        onDeviceNameChange = {deviceName = it},
                        deviceName = deviceName,
                        onAutoFinishChange = {autoFinish = it},
                        onSaveToFolderChange = {saveToFolder = it}
                    )
                }
            }
        }
        // crash screen
        CrashHandler.init(applicationContext,CrashScreenActivity::class.java)
        if (!isBatteryOptimizationDisabled(this)) {
            promptDisableBatteryOptimization(this)
        }
    }

    private fun updateLocale(languageCode: String): Locale {
        val locale = if (languageCode == "System") Locale.getDefault() else Locale(languageCode)
        val config = resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        return locale
    }
}

@Composable
fun BottomNavApp(di: DI,
                 startDestination: String,
                 onThemeChange: (AppTheme) -> Unit,
                 onLanguageChange: (String) -> Unit,
                 onNavigateToScreen: (String) -> Unit,
                 onRestartServer: () -> Unit,
                 onDeviceNameChange: (String) -> Unit,
                 deviceName: String,
                 onAutoFinishChange: (Boolean) -> Unit,
                 onSaveToFolderChange: (String) -> Unit
) = withDI(di)
{

    val navController = rememberNavController()
    // Observe the current route directly through the back stack entry
    val currentRoute = navController.currentBackStackEntryFlow.collectAsState(initial = null)

    LaunchedEffect(currentRoute.value?.destination?.route) {
        if(currentRoute.value?.destination?.route == BottomNavItem.Settings.route){
            currentRoute.value?.destination?.route?.let { route ->
                onNavigateToScreen(route)
            }
        }
    }

    Scaffold(
        bottomBar = { BottomNavigationBar(navController) }
    ){ innerPadding ->
        NavHost(navController, startDestination = startDestination, Modifier.padding(innerPadding))
        {
            composable(BottomNavItem.Home.route) { HomeScreen(
                onSwitchToSearchScreen = {
                    navController.navigate("searchScreen")
                },
                deviceName = deviceName
            ) }
            composable(BottomNavItem.Network.route) { NetworkScreen(
                onClickNetworkNode = { ip ->
                    navController.navigate("chatScreen/${ip}")
                }
            ) }
            composable("chatScreen/{ip}"){ entry ->
                val ip = entry.arguments?.getString("ip")
                    ?: throw IllegalArgumentException("Invalid address")
                ChatScreen(
                    virtualAddress = InetAddress.getByName(ip),
                    onClickButton = {
                        navController.navigate("pingScreen/${ip}")
                    }
                )
            }
            composable("pingScreen/{ip}"){ entry ->
                val ip = entry.arguments?.getString("ip")
                    ?: throw IllegalArgumentException("Invalid address")
                PingScreen(
                    virtualAddress = InetAddress.getByName(ip)
                )
            }
            composable("searchScreen"){
                val isSearching = remember { mutableStateOf(true) }
                SearchScreen(
                    isSearching = isSearching,
                    onHomeClick = {
                        isSearching.value = false // Set to false on cancel
                        navController.popBackStack()
                    }
                )
            }
            composable(BottomNavItem.Send.route) {
                val activity = LocalContext.current as ComponentActivity
                val sharedUrisViewModel: SharedUriViewModel = viewModel(activity)
                SendScreen(
                    onSwitchToSelectDestNode = { uris ->
                        Log.d("uri_track_nav_send", "size: " + uris.size.toString())
                        Log.d("uri_track_nav_send", "List: $uris")
                        sharedUrisViewModel.setUris(uris)
                        navController.navigate("selectDestNode")
                    }
                )
            }
            composable("selectDestNode"){
                val activity = LocalContext.current as ComponentActivity
                val sharedUrisViewModel: SharedUriViewModel = viewModel(activity)
                val sendUris by sharedUrisViewModel.uris.collectAsState()
                Log.d("uri_track_nav_selectDestNode", "size: " + sendUris.size.toString())
                Log.d("uri_track_nav_selectDestNode", "List: $sendUris")
                SelectDestNodeScreen(
                    uris = sendUris,
                    popBackWhenDone = {navController.popBackStack()},
                )
            }
            composable(BottomNavItem.Receive.route) { ReceiveScreen(
                onAutoFinishChange = onAutoFinishChange
            ) }
            composable(BottomNavItem.Settings.route) {
                SettingsScreen(
                    onThemeChange = onThemeChange,
                    onLanguageChange = onLanguageChange,
                    onRestartServer = onRestartServer,
                    onDeviceNameChange = onDeviceNameChange,
                    onAutoFinishChange = onAutoFinishChange,
                    onSaveToFolderChange = onSaveToFolderChange
                )
            }
        }
    }
}

@SuppressLint("ServiceCast", "ObsoleteSdkInt")
fun isBatteryOptimizationDisabled(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    } else {
        true // Battery optimization doesn't apply below Android 6.0
    }
}

@SuppressLint("ObsoleteSdkInt")
fun promptDisableBatteryOptimization(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        AlertDialog.Builder(context)
            .setTitle("Disable Battery Optimization")
            .setMessage(
                "To ensure uninterrupted background functionality and maintain a stable connection," +
                        " please disable battery optimization for this app."
            )
            .setPositiveButton("Go to Settings") { _, _ ->
                try {
                    // Navigate to Battery Optimization Settings
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to App Info screen
                    val appSettingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null))
                    context.startActivity(appSettingsIntent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

