package com.opensky.virtuoso.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.opensky.virtuoso.OpenSkyApp
import com.opensky.virtuoso.ui.components.GlassAlertDialog
import com.opensky.virtuoso.ui.components.GlassStyle
import com.opensky.virtuoso.ui.components.GlassSupport
import com.opensky.virtuoso.ui.components.LocalBottomInset
import com.opensky.virtuoso.ui.components.LocalGlass
import com.opensky.virtuoso.ui.components.OpenSkyIcons
import com.opensky.virtuoso.ui.components.SkyBackdrop
import com.opensky.virtuoso.ui.components.glassPanel
import com.opensky.virtuoso.ui.components.glassSource
import com.opensky.virtuoso.ui.components.rememberGlassBackdropState
import com.opensky.virtuoso.ui.screens.AlignmentScreen
import com.opensky.virtuoso.ui.screens.CatalogScreen
import com.opensky.virtuoso.ui.screens.ControlScreen
import com.opensky.virtuoso.ui.screens.HomeScreen
import com.opensky.virtuoso.ui.screens.MoreScreen
import com.opensky.virtuoso.ui.screens.SensorScreen
import com.opensky.virtuoso.ui.screens.SettingsScreen
import com.opensky.virtuoso.ui.screens.StellariumScreen
import com.opensky.virtuoso.ui.screens.TargetsScreen
import com.opensky.virtuoso.ui.screens.UpdateScreen
import com.opensky.virtuoso.ui.theme.OpenSkyTheme
import com.opensky.virtuoso.ui.viewmodel.AlignmentViewModel
import com.opensky.virtuoso.ui.viewmodel.ControlViewModel
import com.opensky.virtuoso.ui.viewmodel.HomeViewModel
import com.opensky.virtuoso.ui.viewmodel.MoreViewModel
import com.opensky.virtuoso.ui.viewmodel.SensorViewModel
import com.opensky.virtuoso.ui.viewmodel.TargetsViewModel
import com.opensky.virtuoso.ui.viewmodel.UpdateState
import com.opensky.virtuoso.ui.viewmodel.UpdateViewModel
import com.opensky.virtuoso.ui.viewmodel.appViewModelFactory
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", "Inicio", OpenSkyIcons.Home),
    Tab("align", "Alinear", OpenSkyIcons.Target),
    Tab("targets", "Objetivos", OpenSkyIcons.Star),
    Tab("control", "Control", OpenSkyIcons.Pad),
    Tab("more", "Más", OpenSkyIcons.More),
)

private val GLASS_BAR_HEIGHT = 72.dp

private val TITLES = mapOf(
    "home" to "OpenSky Virtuoso Controller", "align" to "Alineación", "targets" to "Objetivos",
    "control" to "Mando manual", "more" to "Más", "sensors" to "Orientación por sensores",
    "stellarium" to "Puente Stellarium", "catalog" to "Catálogo Tycho-2", "settings" to "Ajustes",
    "update" to "Actualizar la app",
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OpenSkyApp).container

        // Pantalla encendida y brillo mínimo en modo nocturno (sin permisos especiales).
        lifecycleScope.launch {
            container.settings.settings.map { it.nightMode to it.keepScreenOn }.distinctUntilChanged().collect { (night, keepOn) ->
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.attributes = window.attributes.apply {
                    screenBrightness = if (night) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            // Cristal solo en Android 16+, y con interruptor propio para el tema azul y el nocturno.
            val glass = GlassStyle(
                enabled = GlassSupport.available && (if (settings.nightMode) settings.glassNight else settings.glassDay),
                night = settings.nightMode,
            )
            OpenSkyTheme(redNightMode = settings.nightMode) {
                CompositionLocalProvider(LocalGlass provides glass) {
                    OpenSkyRoot(appViewModelFactory(container))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenSkyRoot(factory: androidx.lifecycle.ViewModelProvider.Factory) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val homeVm: HomeViewModel = viewModel(factory = factory)

    val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { homeVm.onPermissionsResult() }
    LaunchedEffect(Unit) { launcher.launch(permissions) }

    // Alarmas de seguridad de la montura (reinicio, atasco, parada no confirmada…): se muestran
    // encima de cualquier pantalla hasta que el usuario las lee.
    val mountState by homeVm.mount.collectAsStateWithLifecycle()
    mountState?.alert?.let { alert ->
        GlassAlertDialog(
            onDismissRequest = {},
            title = { Text("⚠ Atención") },
            text = { Text(alert) },
            confirmButton = { TextButton(onClick = { homeVm.clearAlert() }) { Text("Entendido") } },
        )
    }

    // Al abrir la app, comprueba en silencio si hay una versión nueva en GitHub y lo avisa.
    val updateVm: UpdateViewModel = viewModel(factory = factory)
    val updateState by updateVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { updateVm.check(silent = true) }
    LaunchedEffect(updateState) {
        val s = updateState
        if (s is UpdateState.Available) {
            val r = snackbar.showSnackbar("Versión ${s.release.versionName} disponible", actionLabel = "Ver", withDismissAction = true)
            if (r == SnackbarResult.ActionPerformed) nav.navigate("update")
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"

    val glass = LocalGlass.current
    val backdrop = rememberGlassBackdropState()
    val tabs: @Composable RowScope.() -> Unit = {
        TABS.forEach { tab ->
            NavigationBarItem(
                selected = route == tab.route,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
    // Con cristal la barra inferior flota sobre el contenido: las pantallas dejan libre ese espacio al final.
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val floatingBarSpace = if (glass.enabled) navInset + GLASS_BAR_HEIGHT + 16.dp else 0.dp

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (glass.enabled) Modifier.glassSource(backdrop) else Modifier)) {
            if (glass.enabled) SkyBackdrop(glass.night)
            Scaffold(
                containerColor = if (glass.enabled) Color.Transparent else MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = { Text(TITLES[route] ?: "OpenSky") },
                        colors = if (glass.enabled) TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent,
                        ) else TopAppBarDefaults.topAppBarColors(),
                    )
                },
                snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = if (glass.enabled) GLASS_BAR_HEIGHT + 16.dp else 0.dp)) },
                bottomBar = { if (!glass.enabled) NavigationBar(content = tabs) },
            ) { padding ->
                val contentPadding = if (glass.enabled) PaddingValues(top = padding.calculateTopPadding()) else padding
                CompositionLocalProvider(LocalBottomInset provides floatingBarSpace) {
                    NavHost(nav, startDestination = "home", modifier = Modifier.padding(contentPadding)) {
                        composable("home") { HomeScreen(homeVm, snackbar) { nav.navigate("sensors") } }
                        composable("align") {
                            AlignmentScreen(viewModel<AlignmentViewModel>(factory = factory), viewModel<ControlViewModel>(factory = factory), snackbar)
                        }
                        composable("targets") { TargetsScreen(viewModel<TargetsViewModel>(factory = factory), snackbar) }
                        composable("control") { ControlScreen(viewModel<ControlViewModel>(factory = factory), snackbar) }
                        composable("more") { MoreScreen { nav.navigate(it) } }
                        composable("sensors") { SensorScreen(viewModel<SensorViewModel>(factory = factory), snackbar) }
                        composable("stellarium") { StellariumScreen(viewModel<MoreViewModel>(factory = factory), snackbar) }
                        composable("catalog") { CatalogScreen(viewModel<MoreViewModel>(factory = factory), snackbar) }
                        composable("settings") { SettingsScreen(viewModel<MoreViewModel>(factory = factory), snackbar) }
                        composable("update") { UpdateScreen(viewModel<UpdateViewModel>(factory = factory), snackbar) }
                    }
                }
            }
        }
        if (glass.enabled) {
            // Barra de pestañas de cristal: flota sobre las pantallas y desenfoca lo que pasa por detrás.
            val shape = RoundedCornerShape(36.dp)
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .height(GLASS_BAR_HEIGHT)
                    .glassPanel(backdrop, shape, glass)
                    .clip(shape),
                verticalAlignment = Alignment.CenterVertically,
                content = tabs,
            )
        }
    }
}
