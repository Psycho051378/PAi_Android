package com.pai.android.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.google.accompanist.navigation.animation.AnimatedNavHost
import com.google.accompanist.navigation.animation.rememberAnimatedNavController
import com.google.accompanist.navigation.animation.composable
import com.pai.android.ui.screens.AboutScreen
import com.pai.android.ui.screens.LogTerminalScreen
import com.pai.android.ui.screens.AppearanceSettingsScreen
import com.pai.android.ui.screens.CameraScreen
import com.pai.android.ui.screens.ChatDetailScreen
import com.pai.android.ui.screens.ChatListScreen
import com.pai.android.ui.screens.MemoryManagementScreen
import com.pai.android.ui.screens.PermissionsScreen
import com.pai.android.ui.screens.SchedulerTasksScreen
import com.pai.android.ui.screens.SkillStoreScreen
import com.pai.android.ui.screens.ProviderSettingsScreen
import com.pai.android.ui.screens.RoleListScreen
import com.pai.android.ui.screens.SettingsScreen
import com.pai.android.ui.screens.VoiceSettingsScreen
import com.pai.android.ui.screens.RouterSettingsScreen
import com.pai.android.ui.screens.WebSearchSettingsScreen
import com.pai.android.ui.screens.ProactiveSettingsScreen
import androidx.navigation.NavGraphBuilder
import com.google.accompanist.navigation.animation.navigation

/**
 * Конфигурации анимаций для различных экранов.
 */
object NavigationAnimations {
    // Fade анимация (для настроек, вспомогательных экранов)
    val fadeInAnim = fadeIn(animationSpec = tween(200))
    val fadeOutAnim = fadeOut(animationSpec = tween(200))
    
    // Slide горизонтально (для основного перехода)
    val slideInHorizontalAnim = slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(300)
    )
    val slideOutHorizontalAnim = slideOutHorizontally(
        targetOffsetX = { fullWidth -> -fullWidth },
        animationSpec = tween(300)
    )
    
    // Slide вертикально (для камеры, модальных окон)
    val slideInVerticalAnim = slideInVertically(
        initialOffsetY = { fullHeight -> fullHeight },
        animationSpec = tween(350)
    )
    val slideOutVerticalAnim = slideOutVertically(
        targetOffsetY = { fullHeight -> -fullHeight },
        animationSpec = tween(350)
    )
    
    // Slide горизонтально (обратное направление - для возврата назад)
    val slideInHorizontalReverseAnim = slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth },
        animationSpec = tween(300)
    )
    val slideOutHorizontalReverseAnim = slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(300)
    )
}

/**
 * Маршруты приложения.
 */
sealed class Screen(val route: String) {
    object ChatList : Screen("chat_list")
    object ChatDetail : Screen("chat_detail/{chatId}") {
        fun createRoute(chatId: String) = "chat_detail/$chatId"
    }
    object Settings : Screen("settings")
    object ProviderSettings : Screen("provider_settings")
    object RoleList : Screen("role_list")
    object Camera : Screen("camera")
    object WebSearchSettings : Screen("web_search_settings")
    object AppearanceSettings : Screen("appearance_settings")
    object ProactiveSettings : Screen("proactive_settings")
    object About : Screen("about")
    object LogTerminal : Screen("log_terminal")
    object MemoryManagement : Screen("memory_management")
    object SchedulerTasks : Screen("scheduler_tasks")
    object SkillStore : Screen("skill_store")
    object VoiceSettings : Screen("voice_settings")
    object RouterSettings : Screen("router_settings")
    object Permissions : Screen("permissions")

}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun PaiNavigation(
    navController: NavHostController = rememberAnimatedNavController(),
    startDestination: String = Screen.ChatList.route
) {
    AnimatedNavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Главный экран чатов - без анимации при старте
        composable(
            route = Screen.ChatList.route,
            enterTransition = { null },
            exitTransition = { null },
            popEnterTransition = { NavigationAnimations.fadeInAnim },
            popExitTransition = { NavigationAnimations.fadeOutAnim }
        ) {
            ChatListScreen(
                navController = navController,
                onChatClick = { chatId ->
                    navController.navigate(Screen.ChatDetail.createRoute(chatId))
                }
            )
        }
        
        // Экран деталей чата - слайд горизонтально
        composable(
            route = Screen.ChatDetail.route,
            enterTransition = { NavigationAnimations.slideInHorizontalAnim },
            exitTransition = { NavigationAnimations.slideOutHorizontalAnim },
            popEnterTransition = { NavigationAnimations.slideInHorizontalReverseAnim },
            popExitTransition = { NavigationAnimations.slideOutHorizontalReverseAnim }
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
            ChatDetailScreen(chatId = chatId, navController = navController)
        }
        
        // Главный экран настроек - fade анимация
        composable(
            route = Screen.Settings.route,
            enterTransition = { NavigationAnimations.fadeInAnim },
            exitTransition = { NavigationAnimations.fadeOutAnim },
            popEnterTransition = { NavigationAnimations.fadeInAnim },
            popExitTransition = { NavigationAnimations.fadeOutAnim }
        ) {
            SettingsScreen(navController = navController)
        }
        
        // Настройки провайдеров - слайд горизонтально
        composable(
            route = Screen.ProviderSettings.route,
            enterTransition = { NavigationAnimations.slideInHorizontalAnim },
            exitTransition = { NavigationAnimations.slideOutHorizontalAnim },
            popEnterTransition = { NavigationAnimations.slideInHorizontalReverseAnim },
            popExitTransition = { NavigationAnimations.slideOutHorizontalReverseAnim }
        ) {
            ProviderSettingsScreen(navController = navController)
        }
        
        // Список ролей - слайд горизонтально
        composable(
            route = Screen.RoleList.route,
            enterTransition = { NavigationAnimations.slideInHorizontalAnim },
            exitTransition = { NavigationAnimations.slideOutHorizontalAnim },
            popEnterTransition = { NavigationAnimations.slideInHorizontalReverseAnim },
            popExitTransition = { NavigationAnimations.slideOutHorizontalReverseAnim }
        ) {
            RoleListScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        
        // Экран камеры - слайд снизу (как модальное окно)
        composable(
            route = Screen.Camera.route,
            enterTransition = { NavigationAnimations.slideInVerticalAnim },
            exitTransition = { NavigationAnimations.slideOutVerticalAnim },
            popEnterTransition = { NavigationAnimations.slideInVerticalAnim },
            popExitTransition = { NavigationAnimations.slideOutVerticalAnim }
        ) {
            CameraScreen(navController = navController)
        }
        
        // Настройки веб-поиска - слайд горизонтально
        composable(
            route = Screen.WebSearchSettings.route,
            enterTransition = { NavigationAnimations.slideInHorizontalAnim },
            exitTransition = { NavigationAnimations.slideOutHorizontalAnim },
            popEnterTransition = { NavigationAnimations.slideInHorizontalReverseAnim },
            popExitTransition = { NavigationAnimations.slideOutHorizontalReverseAnim }
        ) {
            WebSearchSettingsScreen(navController = navController)
        }
        
        // Настройки внешнего вида - слайд горизонтально
        composable(
            route = Screen.AppearanceSettings.route,
            enterTransition = { NavigationAnimations.slideInHorizontalAnim },
            exitTransition = { NavigationAnimations.slideOutHorizontalAnim },
            popEnterTransition = { NavigationAnimations.slideInHorizontalReverseAnim },
            popExitTransition = { NavigationAnimations.slideOutHorizontalReverseAnim }
        ) {
            AppearanceSettingsScreen(navController = navController)
        }
        composable(route = Screen.ProactiveSettings.route) {
            ProactiveSettingsScreen(onBack = { navController.popBackStack() })
        }
        
        // Экран "О приложении" - слайд горизонтально
        composable(
            route = Screen.About.route,
            enterTransition = { NavigationAnimations.slideInHorizontalAnim },
            exitTransition = { NavigationAnimations.slideOutHorizontalAnim },
            popEnterTransition = { NavigationAnimations.slideInHorizontalReverseAnim },
            popExitTransition = { NavigationAnimations.slideOutHorizontalReverseAnim }
        ) {
            AboutScreen(navController = navController)
        }
        
        // Log Terminal
        composable(
            route = Screen.LogTerminal.route
        ) {
            LogTerminalScreen(navController = navController)
        }
        
        // Экран управления памятью - fade анимация
        composable(
            route = Screen.MemoryManagement.route,
            enterTransition = { NavigationAnimations.fadeInAnim },
            exitTransition = { NavigationAnimations.fadeOutAnim },
            popEnterTransition = { NavigationAnimations.fadeInAnim },
            popExitTransition = { NavigationAnimations.fadeOutAnim }
        ) {
            MemoryManagementScreen(navController = navController)
        }

        // Scheduler tasks screen
        composable(
            route = Screen.SchedulerTasks.route,
            enterTransition = { NavigationAnimations.slideInHorizontalAnim },
            exitTransition = { NavigationAnimations.slideOutHorizontalAnim },
            popEnterTransition = { NavigationAnimations.slideInHorizontalReverseAnim },
            popExitTransition = { NavigationAnimations.slideOutHorizontalReverseAnim }
        ) {
            SchedulerTasksScreen(navController = navController)
        }
        // Skill store screen
        composable(
            route = Screen.SkillStore.route,
            enterTransition = { NavigationAnimations.slideInHorizontalAnim },
            exitTransition = { NavigationAnimations.slideOutHorizontalAnim },
            popEnterTransition = { NavigationAnimations.slideInHorizontalReverseAnim },
            popExitTransition = { NavigationAnimations.slideOutHorizontalReverseAnim }
        ) {
            SkillStoreScreen(navController = navController)
        }
        composable(
            route = Screen.RouterSettings.route,
            enterTransition = { NavigationAnimations.fadeInAnim },
            exitTransition = { NavigationAnimations.fadeOutAnim },
            popEnterTransition = { NavigationAnimations.fadeInAnim },
            popExitTransition = { NavigationAnimations.fadeOutAnim }
        ) {
            RouterSettingsScreen(navController = navController)
        }

        composable(
            route = Screen.VoiceSettings.route,
            enterTransition = { NavigationAnimations.fadeInAnim },
            exitTransition = { NavigationAnimations.fadeOutAnim },
            popEnterTransition = { NavigationAnimations.fadeInAnim },
            popExitTransition = { NavigationAnimations.fadeOutAnim }
        ) {
            VoiceSettingsScreen(
                onNavigateBack = { navController.navigateUp() }
            )
        }

        // Экран разрешений (онбординг) — без анимации возврата
        composable(
            route = Screen.Permissions.route,
            enterTransition = { null },
            exitTransition = { null },
            popEnterTransition = { NavigationAnimations.fadeInAnim },
            popExitTransition = { NavigationAnimations.fadeOutAnim }
        ) {
            PermissionsScreen(
                onComplete = {
                    navController.navigate(Screen.ChatList.route) {
                        popUpTo(Screen.Permissions.route) { inclusive = true }
                    }
                },
                onSkip = {
                    navController.navigate(Screen.ChatList.route) {
                        popUpTo(Screen.Permissions.route) { inclusive = true }
                    }
                }
            )
        }

    }
}
