package dev.gamblock.shield

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ProcessLifecycleOwner
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
}

@Composable
fun ShieldApp(
    onEnableProtection: () -> Unit,
    onDisableProtection: () -> Unit,
) {
    val navController = rememberNavController()
    val onboardingViewModel: OnboardingViewModel = hiltViewModel()
    val onboardingComplete by onboardingViewModel.alreadyCompleted.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = if (onboardingComplete) "dashboard" else "onboarding",
    ) {
        composable("onboarding") {
            dev.gamblock.feature.onboarding.OnboardingRoute(
                onDone = {
                    navController.navigate("dashboard") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                },
            )
        }
        composable("setup") {
            dev.gamblock.feature.setup.SetupRoute(
                onDone = { navController.popBackStack() },
                onEnableProtection = onEnableProtection,
                onDisableProtection = onDisableProtection,
            )
        }
        composable("dashboard") {
            dev.gamblock.feature.dashboard.DashboardRoute(
                onEnableProtection = onEnableProtection,
                onDisableProtection = onDisableProtection,
                onOpenReports = { navController.navigate("reports") },
                onOpenDiagnostics = { navController.navigate("diagnostics") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenSupport = { navController.navigate("support") },
            )
        }
        composable("reports") {
            dev.gamblock.feature.reports.ReportsRoute(onBack = { navController.popBackStack() })
        }
        composable("diagnostics") {
            dev.gamblock.feature.diagnostics.DiagnosticsRoute(onBack = { navController.popBackStack() })
        }
        composable("settings") {
            dev.gamblock.feature.settings.SettingsRoute(
                onBack = { navController.popBackStack() },
                onOpenAccountability = { navController.navigate("accountability") },
                onOpenParent = { navController.navigate("parent") },
            )
        }
        composable("accountability") {
            dev.gamblock.feature.accountability.AccountabilityRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable("parent") {
            dev.gamblock.feature.parent.ParentRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable("support") {
            dev.gamblock.feature.support.SupportRoute(onBack = { navController.popBackStack() })
        }
    }
}