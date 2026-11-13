package com.replyai.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.replyai.app.ui.screens.BotBuilderScreen
import com.replyai.app.ui.screens.BotsScreen
import com.replyai.app.ui.screens.DashboardScreen
import com.replyai.app.ui.screens.KnowledgeScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Outlined.Dashboard)
    data object Bots : Screen("bots", "Bots", Icons.Outlined.SmartToy)
    data object Knowledge : Screen("knowledge", "Knowledge", Icons.Outlined.Chat)
    data object BotBuilder : Screen("bot_builder", "Build", Icons.Outlined.Chat)
}

@Composable
fun ReplyAINavHost() {
    val navController = rememberNavController()
    val screens = listOf(Screen.Dashboard, Screen.Bots, Screen.Knowledge, Screen.BotBuilder)

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen() }
            composable(Screen.Bots.route) { BotsScreen() }
            composable(Screen.Knowledge.route) { KnowledgeScreen() }
            composable(Screen.BotBuilder.route) { BotBuilderScreen() }
        }
    }
}
