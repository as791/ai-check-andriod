package com.aicheck.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aicheck.app.ui.analyzing.AnalyzingScreen
import com.aicheck.app.ui.history.HistoryScreen
import com.aicheck.app.ui.home.HomeScreen
import com.aicheck.app.ui.result.ResultScreen
import com.aicheck.app.ui.settings.SettingsScreen

@Composable
fun AiCheckNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onImageSelected = { uri -> navController.navigate(Routes.analyzing(uri, isVideo = false)) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenHistoryItem = { id -> navController.navigate(Routes.result(id)) },
            )
        }
        composable(
            route = Routes.ANALYZING_PATTERN,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("isVideo") { type = NavType.BoolType },
            ),
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri").orEmpty()
            val isVideo = backStackEntry.arguments?.getBoolean("isVideo") ?: false
            AnalyzingScreen(
                encodedUri = encodedUri,
                isVideo = isVideo,
                onComplete = { analysisId ->
                    navController.navigate(Routes.result(analysisId)) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                },
                onCancel = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(
            route = Routes.RESULT_PATTERN,
            arguments = listOf(navArgument("analysisId") { type = NavType.StringType }),
        ) {
            ResultScreen(onCheckAnother = { navController.popBackStack(Routes.HOME, inclusive = false) })
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenItem = { id -> navController.navigate(Routes.result(id)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
