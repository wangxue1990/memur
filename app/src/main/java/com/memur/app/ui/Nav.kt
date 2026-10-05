package com.memur.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.memur.app.MemurApp
import com.memur.app.ui.screens.ChatScreen
import com.memur.app.ui.screens.NoteDetailScreen
import com.memur.app.ui.screens.RemindersScreen
import com.memur.app.ui.screens.SettingsScreen
import com.memur.app.ui.screens.TopicDetailScreen
import com.memur.app.ui.screens.WikiScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "首页", Icons.Filled.Home),
    Tab("wiki", "知识库", Icons.Filled.MenuBook),
    Tab("reminders", "提醒", Icons.Outlined.Alarm),
    Tab("settings", "设置", Icons.Filled.Settings)
)

@Composable
fun AppRoot(container: MemurApp.Container) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // 深链导航
    LaunchedEffect(Unit) {
        NavBus.deepLink.collect { target ->
            if (target != null) {
                navController.navigate(target) { launchSingleTop = true }
                NavBus.deepLink.value = null
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (currentRoute in tabs.map { it.route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") {
                ChatScreen(container = container, nav = navController)
            }
            composable("wiki") {
                WikiScreen(container = container, nav = navController)
            }
            composable("topic/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: -1L
                TopicDetailScreen(container = container, nav = navController, topicId = id)
            }
            composable("note/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: -1L
                NoteDetailScreen(container = container, nav = navController, noteId = id)
            }
            composable("reminders") {
                RemindersScreen(container = container, nav = navController)
            }
            composable("settings") {
                SettingsScreen(container = container, nav = navController)
            }
        }
    }
}
