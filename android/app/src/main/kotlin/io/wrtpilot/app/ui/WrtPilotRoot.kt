package io.wrtpilot.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.wrtpilot.app.DeepLink
import io.wrtpilot.app.R
import io.wrtpilot.app.ui.dashboard.DashboardScreen
import io.wrtpilot.app.ui.devices.DeviceDetailScreen
import io.wrtpilot.app.ui.devices.DevicesScreen
import io.wrtpilot.app.ui.groups.GroupDetailScreen
import io.wrtpilot.app.ui.groups.GroupsScreen
import io.wrtpilot.app.ui.navigation.DeviceRoute
import io.wrtpilot.app.ui.navigation.DevicesRoute
import io.wrtpilot.app.ui.navigation.GroupRoute
import io.wrtpilot.app.ui.navigation.GroupsRoute
import io.wrtpilot.app.ui.navigation.HomeRoute
import io.wrtpilot.app.ui.navigation.QosRoute
import io.wrtpilot.app.ui.navigation.RouterFormRoute
import io.wrtpilot.app.ui.navigation.RoutersRoute
import io.wrtpilot.app.ui.navigation.SettingsRoute
import io.wrtpilot.app.ui.navigation.WelcomeRoute
import io.wrtpilot.app.ui.onboarding.RouterFormScreen
import io.wrtpilot.app.ui.onboarding.WelcomeScreen
import io.wrtpilot.app.ui.qos.QosScreen
import io.wrtpilot.app.ui.settings.RoutersScreen
import io.wrtpilot.app.ui.settings.SettingsScreen
import io.wrtpilot.app.ui.theme.WrtPilotTheme
import kotlinx.coroutines.flow.StateFlow

@Composable
fun WrtPilotRoot(
    deepLinks: StateFlow<DeepLink?>,
    onDeepLinkHandled: () -> Unit,
) {
    val vm: MainViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    WrtPilotTheme(themeMode = state.settings.theme, dynamicColor = state.settings.dynamicColor) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize())
                state.routers.isEmpty() -> OnboardingNav()
                else -> MainNav(vm, deepLinks, onDeepLinkHandled)
            }
        }
    }
}

/** First run: welcome, then add the first router. */
@Composable
private fun OnboardingNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = WelcomeRoute) {
        composable<WelcomeRoute> {
            WelcomeScreen(onStart = { nav.navigate(RouterFormRoute()) })
        }
        composable<RouterFormRoute> {
            RouterFormScreen(onBack = { nav.popBackStack() }, onDone = { /* root switches to the main UI */ })
        }
    }
}

private data class Tab(val route: Any, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, R.string.tab_home, Icons.Rounded.Home),
    Tab(DevicesRoute(), R.string.tab_devices, Icons.Rounded.Devices),
    Tab(GroupsRoute, R.string.tab_family, Icons.Rounded.FamilyRestroom),
    Tab(SettingsRoute, R.string.tab_settings, Icons.Rounded.Settings),
)

@Composable
private fun MainNav(
    vm: MainViewModel,
    deepLinks: StateFlow<DeepLink?>,
    onDeepLinkHandled: () -> Unit,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val deepLink by deepLinks.collectAsStateWithLifecycle()

    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        vm.selectRouter(link.routerId)
        link.mac?.let { nav.navigate(DeviceRoute(it)) }
        onDeepLinkHandled()
    }

    val showBar = destination?.let { d ->
        d.hasRoute<HomeRoute>() || d.hasRoute<DevicesRoute>() || d.hasRoute<GroupsRoute>() || d.hasRoute<SettingsRoute>()
    } ?: true

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    for (tab in tabs) {
                        val selected = destination?.hierarchy?.any { d ->
                            when (tab.route) {
                                is HomeRoute -> d.hasRoute<HomeRoute>()
                                is DevicesRoute -> d.hasRoute<DevicesRoute>()
                                is GroupsRoute -> d.hasRoute<GroupsRoute>()
                                else -> d.hasRoute<SettingsRoute>()
                            }
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = HomeRoute,
            modifier = Modifier
                .padding(bottom = padding.calculateBottomPadding())
                // only the bottom bar is applied here: each screen's top bar pads for the status bar
                .consumeWindowInsets(PaddingValues(bottom = padding.calculateBottomPadding())),
        ) {
            composable<HomeRoute> {
                DashboardScreen(
                    onOpenDevices = { filter -> nav.navigate(DevicesRoute(filter)) },
                    onOpenDevice = { nav.navigate(DeviceRoute(it)) },
                    onOpenQos = { nav.navigate(QosRoute) },
                    onOpenGroups = { nav.navigateTab(GroupsRoute) },
                    onManageRouters = { nav.navigate(RoutersRoute) },
                )
            }
            composable<DevicesRoute> {
                // the initial filter is read from the route by DevicesViewModel
                DevicesScreen(
                    onOpenDevice = { nav.navigate(DeviceRoute(it)) },
                    onManageRouters = { nav.navigate(RoutersRoute) },
                )
            }
            composable<DeviceRoute> {
                DeviceDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenGroup = { nav.navigate(GroupRoute(it)) },
                )
            }
            composable<GroupsRoute> {
                GroupsScreen(
                    onOpenGroup = { nav.navigate(GroupRoute(it)) },
                    onManageRouters = { nav.navigate(RoutersRoute) },
                )
            }
            composable<GroupRoute> {
                GroupDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenDevice = { nav.navigate(DeviceRoute(it)) },
                )
            }
            composable<QosRoute> {
                QosScreen(onBack = { nav.popBackStack() })
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onOpenRouters = { nav.navigate(RoutersRoute) },
                    onOpenQos = { nav.navigate(QosRoute) },
                )
            }
            composable<RoutersRoute> {
                RoutersScreen(
                    onBack = { nav.popBackStack() },
                    onAdd = { nav.navigate(RouterFormRoute()) },
                    onEdit = { nav.navigate(RouterFormRoute(it)) },
                )
            }
            composable<RouterFormRoute> {
                RouterFormScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
            }
        }
    }
}

/** Bottom bar navigation: one copy of each tab, state restored. */
private fun NavHostController.navigateTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
