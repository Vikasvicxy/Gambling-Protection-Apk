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