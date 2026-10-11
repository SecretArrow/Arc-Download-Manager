package com.arc.downloadmanager.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arc.downloadmanager.R
import com.arc.downloadmanager.ui.browser.BrowserScreen
import com.arc.downloadmanager.ui.downloads.DownloadDetailScreen
import com.arc.downloadmanager.ui.files.FilesScreen
import com.arc.downloadmanager.ui.home.HomeScreen
import com.arc.downloadmanager.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val BROWSER = "browser"
    const val FILES = "files"
    const val SETTINGS = "settings"
    const val DOWNLOAD_DETAIL = "download/{id}"

    fun downloadDetail(id: String) = "download/$id"
}

private data class Destination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
fun ArcApp() {
    val navController = rememberNavController()
    val destinations =
        listOf(
            Destination(Routes.HOME, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
            Destination(Routes.BROWSER, R.string.nav_browser, Icons.Filled.Public, Icons.Outlined.Public),
            Destination(Routes.FILES, R.string.nav_files, Icons.Filled.Description, Icons.Outlined.Description),
            Destination(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
        )
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBars = currentRoute in destinations.map { it.route }
    val compact = LocalConfiguration.current.screenWidthDp < 600

    val bottomBar: @Composable () -> Unit = {
        if (showBars) {
            NavigationBar {
                destinations.forEach { dest ->
                    val selected = currentRoute == dest.route
                    val label = stringResource(dest.labelRes)
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navigateTo(navController, dest.route) },
                        icon = {
                            Icon(
                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                contentDescription = label,
                            )
                        },
                        label = { Text(label) },
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
            }
        }
    }
    val rail: @Composable () -> Unit = {
        if (showBars) {
            NavigationRail {
                destinations.forEach { dest ->
                    val selected = currentRoute == dest.route
                    NavigationRailItem(
                        selected = selected,
                        onClick = { navigateTo(navController, dest.route) },
                        icon = {
                            Icon(
                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                contentDescription = stringResource(dest.labelRes),
                            )
                        },
                        label = { Text(stringResource(dest.labelRes)) },
                    )
                }
            }
        }
    }

    if (compact) {
        // WindowInsets(0): the NavigationBar applies the navigation-bar inset itself and each
        // screen's Scaffold/TopAppBar applies the status-bar inset, so system insets are
        // consumed exactly once (no double padding, no content under system bars).
        Scaffold(bottomBar = bottomBar, contentWindowInsets = WindowInsets(0.dp)) { padding ->
            ArcNavHost(navController, Modifier.padding(padding))
        }
    } else {
        Scaffold(contentWindowInsets = WindowInsets(0.dp)) { padding ->
            Row(Modifier.fillMaxSize()) {
                rail()
                ArcNavHost(navController, Modifier.padding(padding))
            }
        }
    }
}

private fun navigateTo(
    navController: NavHostController,
    route: String,
) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun ArcNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenDetail = { id -> navController.navigate(Routes.downloadDetail(id)) },
                    onOpenBrowser = { navigateTo(navController, Routes.BROWSER) },
                    onOpenFiles = { navigateTo(navController, Routes.FILES) },
                )
            }
            composable(Routes.BROWSER) {
                BrowserScreen()
            }
            composable(Routes.FILES) {
                FilesScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
            composable(Routes.DOWNLOAD_DETAIL) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                DownloadDetailScreen(
                    downloadId = id,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
