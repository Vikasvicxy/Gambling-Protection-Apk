plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        failOnNoDiscoveredTests = false
        // The Windows dev machine keeps a custom Maven local repo; Robolectric's runtime
        // dependency resolver reads maven.repo.local to locate android-all-instrumented.
        // On other hosts fall back to the platform default (~/.m2/repository) so the path
        // can never leak a Windows drive letter onto Linux/CI.
        if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
            systemProperty("maven.repo.local", "C:\\grhome-m2")
        }
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

// Google Play listing assets are rendered from code so the screenshots can never drift
// from the app's real palette and copy. Both scripts require Pillow; they are not wired
// into `check` because they are a release-time task, not a build correctness gate.
val storeAssetsPython = "tools/store/generate_store_assets.py"
val validateStoreAssetsPython = "tools/store/validate_store_assets.py"

fun pythonExecutable(): String {
    val override = providers.gradleProperty("pythonExecutable").orNull
    return override?.takeIf { it.isNotBlank() } ?: "python"
}

tasks.register<Exec>("generateStoreAssets") {
    group = "documentation"
    description = "Renders the Play Store screenshots and feature graphic into docs/store."
    workingDir = rootDir
    commandLine(pythonExecutable(), storeAssetsPython)
}

tasks.register<Exec>("validateStoreAssets") {
    group = "verification"
    description = "Checks the rendered Play Store assets for size, mode and content rules."
    workingDir = rootDir
    commandLine(pythonExecutable(), validateStoreAssetsPython)
}