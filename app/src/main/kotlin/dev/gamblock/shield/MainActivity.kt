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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import dev.gamblock.data.preferences.TimingAnchorRepository
import dev.gamblock.data.preferences.VpnDisclosureRepository
import dev.gamblock.data.repository.CommitmentEngine
import dev.gamblock.data.repository.ProtectionEnforcer
import dev.gamblock.feature.onboarding.OnboardingViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var protectionEnforcer: ProtectionEnforcer
    @Inject lateinit var timingAnchorRepository: TimingAnchorRepository
    @Inject lateinit var commitmentEngine: CommitmentEngine
    @Inject lateinit var vpnDisclosureRepository: VpnDisclosureRepository

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            protectionEnforcer.enforceNow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
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
            var showDisclosure by rememberSaveable { mutableStateOf(false) }
            var disclosureBusy by remember { mutableStateOf(false) }
            var disclosureError by remember { mutableStateOf<String?>(null) }

            ShieldTheme {
                ShieldApp(
                    initialDestination = pendingDestination,
                    onEnableProtection = {
                        lifecycleScope.launch {
                            if (vpnDisclosureRepository.isAccepted()) {
                                launchVpnPermissionPrompt()
                            } else {
                                showDisclosure = true
                            }
                        }
                    },
                    onDisableProtection = { protectionEnforcer.stopNow("user toggle") },
                )

                if (showDisclosure) {
                    VpnDisclosureDialog(
                        accepting = disclosureBusy,
                        error = disclosureError,
                        onAccept = {
                            if (!disclosureBusy) {
                                disclosureBusy = true
                                disclosureError = null
                                lifecycleScope.launch {
                                    try {
                                        vpnDisclosureRepository.accept()
                                        showDisclosure = false
                                        launchVpnPermissionPrompt()
                                    } catch (error: kotlinx.coroutines.CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        disclosureError = "Consent could not be saved. Please try again."
                                    } finally {
                                        disclosureBusy = false
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    private fun launchVpnPermissionPrompt() {
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

private val ALLOWED_DESTINATIONS = setOf(
    "onboarding",
    "setup",
    "dashboard",
    "reports",
    "diagnostics",
    "settings",
    "privacy",
    "accountability",
    "parent",
    "support",
)

@Composable
private fun VpnDisclosureDialog(
    accepting: Boolean,
    error: String?,
    onAccept: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Before Android asks for VPN access") },
        text = {
            Column {
                Text("Shield needs Android's VPN permission to filter gambling domains.")
                Spacer(modifier = Modifier.height(8.dp))
                Text("What is handled: DNS hostnames only.")
                Text("Where it is processed: strictly locally on this device.")
                Text("What is not collected: browsing content or personal data. Shield does not transmit that data.")
                Text("Why it is required: Android's local VPN interface lets Shield answer blocked gambling domains and forward other DNS lookups.")
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept, enabled = !accepting) {
                Text(if (accepting) "Saving consent..." else "I Understand & Agree")
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    )
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

    LaunchedEffect(initialDestination, startDestination) {
        if (initialDestination != null &&
            initialDestination != startDestination &&
            initialDestination in ALLOWED_DESTINATIONS
        ) {
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
                onOpenPrivacyPolicy = { navController.navigate("privacy") },
            )
        }
        composable(
            route = "privacy",
            enterTransition = shieldEnter,
            exitTransition = shieldExit,
            popEnterTransition = shieldPopEnter,
            popExitTransition = shieldPopExit,
        ) {
            dev.gamblock.feature.settings.PrivacyPolicyRoute(onBack = { navController.popBackStack() })
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