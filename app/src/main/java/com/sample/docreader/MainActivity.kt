package com.sample.docreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sample.docreader.ui.AppTheme
import com.sample.docreader.ui.FixtureEntryScreen
import com.sample.docreader.ui.SettingsScreen
import com.sample.docreader.ui.reader.ReaderScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val navController = rememberNavController()
                // M3 起点为 fixtures 选择入口；M4 替换为 HomeScreen（最近列表 + SAF）
                NavHost(navController = navController, startDestination = "fixtures") {
                    composable("fixtures") {
                        FixtureEntryScreen(
                            onOpenFixture = { name -> navController.navigate("reader/$name") },
                            onOpenSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("reader/{name}") { entry ->
                        ReaderScreen(
                            name = entry.arguments?.getString("name").orEmpty(),
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
