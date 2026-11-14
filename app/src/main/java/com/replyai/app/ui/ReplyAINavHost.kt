package com.replyai.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.replyai.app.ui.screens.BotBuilderScreen
import com.replyai.app.ui.screens.BotDetailScreen
import com.replyai.app.ui.screens.BotsScreen
import com.replyai.app.ui.screens.DashboardScreen
import com.replyai.app.ui.screens.KnowledgeScreen
import com.replyai.app.ui.screens.SettingsScreen

/**
 * Bottom-nav destinations shown in the Scaffold's NavigationBar.
 *
 * BotBuilder is intentionally NOT in this list — it's reached via a FAB on
 * both Dashboard and Bots (see BUILD_PLAN.md section 3). BotDetailScreen is
 * reached via BotsScreen's edit button and lives on the back stack only.
 */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Outlined.Dashboard)
    data object Bots : Screen("bots", "Bots", Icons.Outlined.SmartToy)
    data object Knowledge : Screen("knowledge", "Knowledge", Icons.Outlined.MenuBook)
    data object Settings : Screen("settings", "Settings", Icons.Outlined.Settings)
}

/**
 * Routes for screens that are reachable from nav actions but NOT shown in the
 * bottom bar. Each composable here declares its own nav arguments.
 */
object Routes {
    const val BOT_BUILDER = "bot_builder"
    const val BOT_DETAIL = "bot_detail/{botId}"
    fun botDetail(id: Long) = "bot_detail/$id"
}

@Composable
fun ReplyAINavHost() {
    val navController = rememberNavController()
    val screens = listOf(Screen.Dashboard, Screen.Bots, Screen.Knowledge, Screen.Settings)

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
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onBuildBot = {
                        navController.navigate(Routes.BOT_BUILDER) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Screen.Bots.route) {
                BotsScreen(
                    onEditBot = { id ->
                        navController.navigate(Routes.botDetail(id))
                    },
                    onBuildCustomBot = {
                        navController.navigate(Routes.BOT_BUILDER) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Screen.Knowledge.route) { KnowledgeScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
            composable(Routes.BOT_BUILDER) {
                BotBuilderScreen(
                    onBack = { navController.popBackStack() },
                    onBotCreated = { id ->
                        // Pop the builder and route into the detail screen for fine-tuning.
                        navController.popBackStack()
                        navController.navigate(Routes.botDetail(id))
                    },
                )
            }
            composable(
                route = Routes.BOT_DETAIL,
                arguments = listOf(
                    navArgument("botId") { type = NavType.LongType },
                ),
            ) { backStackEntry ->
                val botId = backStackEntry.arguments?.getLong("botId") ?: -1L
                BotDetailScreen(
                    botId = botId,
                    onBack = { navController.popBackStack() },
                    onDeleted = { navController.popBackStack() },
                )
            }
        }
    }
}
