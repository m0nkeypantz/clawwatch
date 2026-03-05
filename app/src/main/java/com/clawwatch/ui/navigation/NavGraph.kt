package com.clawwatch.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.clawwatch.ui.MainScreen
import com.clawwatch.ui.SettingsScreen
import com.clawwatch.viewmodel.MainViewModel

object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
}

@Composable
fun NavGraph(launchedAsAssistant: Boolean = false) {
    val navController = rememberSwipeDismissableNavController()
    val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = Routes.MAIN,
    ) {
        composable(Routes.MAIN) {
            MainScreen(
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                viewModel = viewModel,
                launchedAsAssistant = launchedAsAssistant,
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(viewModel = viewModel)
        }
    }
}
