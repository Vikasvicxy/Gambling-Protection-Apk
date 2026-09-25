package dev.gamblock.shield

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.gamblock.core.designsystem.theme.ShieldTheme
import dev.gamblock.data.repository.CommitmentEngine
import dev.gamblock.data.repository.ProtectionEnforcer
import dev.gamblock.data.preferences.TimingAnchorRepository
import dev.gamblock.feature.onboarding.OnboardingViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var protectionEnforcer: ProtectionEnforcer
    @Inject lateinit var timingAnchorRepository: TimingAnchorRepository
    @Inject lateinit var commitmentEngine: CommitmentEngine

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            protectionEnforcer.enforceNow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pendingDestination = intent?.getStringExtra(EXTRA_NAV_DESTINATION)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    timingAnchorRepository.onAppResume()
                    commitmentEngine.tick()
                }
            }
        })

        setContent {
            ShieldTheme {
                ShieldApp(
                    initialDestination = pendingDestination,
                    onEnableProtection = ::requestVpnPermissionAndStart,
                    onDisableProtection = { protectionEnforcer.stopNow("user toggle") },
                )
            }
        }
    }

    private fun requestVpnPermissionAndStart() {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            protectionEnforcer.enforceNow()
        } else {
            vpnPermissionLauncher.launch(intent)
        }
    }

    companion object {
        /** Intent extra used by notification / tile actions to deep-link a screen. */
        const val EXTRA_NAV_DESTINATION = "dev.gamblock.shield.extra.NAV_DESTINATION"
    }
}

private const val ENTER_MS = 260
private const val EXIT_MS = 200

private val shieldEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(ENTER_MS)) + scaleIn(initialScale = 0.95f, animationSpec = tween(ENTER_MS))
}

private val shieldExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(EXIT_MS)) + scaleOut(targetScale = 0.98f, animationSpec = tween(EXIT_MS))
}

private val shieldPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(ENTER_MS)) + scaleIn(initialScale = 0.96f, animationSpec = tween(ENTER_MS))
}

private val shieldPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(EXIT_MS)) + scaleOut(targetScale = 0.99f, animationSpec = tween(EXIT_MS))
}

@Composable
fun ShieldApp(
    initialDestination: String? = null,
    onEnableProtection: () -> Unit,
    onDisableProtection: () -> Unit,
) {
    val navController = rememberNavController()
    val onboardingViewModel: OnboardingViewModel = hiltViewModel()
    val onboardingComplete by onboardingViewModel.alreadyCompleted.collectAsStateWithLifecycle()
    val startDestination = if (onboardingComplete) "dashboard" else "onboarding"

    LaunchedEffect(initialDestination) {
        if (initialDestination != null && initialDestination != startDestination) {
            navController.navigate(initialDestination) {
                launchSingleTop = true
                popUpTo(startDestination) { inclusive = false }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = shieldEnter,
        exitTransition = shieldExit,
        popEnterTransition = shieldPopEnter,
        popExitTransition = shieldPopExit,
    ) {
        composable(
            route = "onboarding",
            enterTransition = shieldEnter,
            exitTransition = shieldExit,
            popEnterTransition = shieldPopEnter,
            popExitTransition = shieldPopExit,
        ) {
            dev.gamblock.feature.onboarding.OnboardingRoute(
                onDone = {
                    navController.navigate("dashboard") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = "setup",
            enterTransition = shieldEnter,
            exitTransition = shieldExit,
            popEnterTransition = shieldPopEnter,
            popExitTransition = shieldPopExit,
        ) {
            dev.gamblock.feature.setup.SetupRoute(
                onDone = { navController.popBackStack() },
                onEnableProtection = onEnableProtection,
                onDisableProtection = onDisableProtection,
            )
        }
        composable(
            route = "dashboard",
            enterTransition = shieldEnter,
            exitTransition = shieldExit,
            popEnterTransition = shieldPopEnter,
            popExitTransition = shieldPopExit,
        ) {
            dev.gamblock.feature.dashboard.DashboardRoute(
                onEnableProtection = onEnableProtection,
                onDisableProtection = onDisableProtection,
                onOpenReports = { navController.navigate("reports") },
                onOpenDiagnostics = { navController.navigate("diagnostics") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenSupport = { navController.navigate("support") },
            )
        }
        composable(
            route = "reports",
            enterTransition = shieldEnter,
            exitTransition = shieldExit,
            popEnterTransition = shieldPopEnter,
            popExitTransition = shieldPopExit,
        ) {
            dev.gamblock.feature.reports.ReportsRoute(onBack = { navController.popBackStack() })
        }
        composable(
            route = "diagnostics",
            enterTransition = shieldEnter,
            exitTransition = shieldExit,
            popEnterTransition = shieldPopEnter,
            popExitTransition = shieldPopExit,
        ) {
            dev.gamblock.feature.diagnostics.DiagnosticsRoute(onBack = { navController.popBackStack() })
        }
        composable(
            route = "settings",
            enterTransition = shieldEnter,
            exitTransition = shieldExit,
            popEnterTransition = shieldPopEnter,
            popExitTransition = shieldPopExit,
        ) {
            dev.gamblock.feature.settings.SettingsRoute(
                onBack = { navController.popBackStack() },
                onOpenAccountability = { navController.navigate("accountability") },
                onOpenParent = { navController.navigate("parent") },
            )
        }
        composable(
            route = "accountability",
            enterTransition = shieldEnter,
            exitTransition = shieldExit,
            popEnterTransition = shieldPopEnter,
            popExitTransition = shieldPopExit,
        ) {
            dev.gamblock.feature.accountability.AccountabilityRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "parent",
            enterTransition = shieldEnter,
            exitTransition = shieldExit,
            popEnterTransition = shieldPopEnter,
            popExitTransition = shieldPopExit,
        ) {
            dev.gamblock.feature.parent.ParentRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "support",
            enterTransition = shieldEnter,
            exitTransition = shieldExit,
            popEnterTransition = shieldPopEnter,
            popExitTransition = shieldPopExit,
        ) {
            dev.gamblock.feature.support.SupportRoute(onBack = { navController.popBackStack() })
        }
    }
}