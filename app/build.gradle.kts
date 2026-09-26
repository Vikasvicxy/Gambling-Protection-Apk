plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

import java.io.File
import java.io.FileInputStream
import java.util.Properties

// --- Release signing ---------------------------------------------------------
// Production signing is opt-in. It activates only when a git-ignored
// keystore.properties is present at the repository root AND every required
// field is populated AND the keystore it points at actually exists. That is
// what scripts/generate-keystore.ps1 (or .sh) writes.
//
// Keystores and passwords are never committed - see .gitignore.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

val requiredSigningProperties = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val missingSigningProperties = requiredSigningProperties
    .filter { keystoreProperties.getProperty(it).isNullOrBlank() }

// storeFile is written relative to the repository root, so resolve it there
// rather than against this (app/) project directory.
val configuredStoreFile: File? = keystoreProperties.getProperty("storeFile")
    ?.takeIf { it.isNotBlank() }
    ?.let { raw ->
        val candidate = File(raw)
        if (candidate.isAbsolute) candidate else rootProject.file(raw)
    }

val hasReleaseSigning = keystorePropertiesFile.exists() &&
    missingSigningProperties.isEmpty() &&
    configuredStoreFile?.isFile == true

// A present-but-broken configuration is a mistake worth failing on: silently
// downgrading it would hand the developer a debug-signed artifact that looks
// exactly like a real release.
if (keystorePropertiesFile.exists() && !hasReleaseSigning) {
    val problems = buildList {
        if (missingSigningProperties.isNotEmpty()) {
            add("keystore.properties is missing: ${missingSigningProperties.joinToString()}")
        }
        if (configuredStoreFile?.isFile != true) {
            add("keystore not found at '${configuredStoreFile?.path ?: "<storeFile>"}'")
        }
    }
    throw GradleException(
        "keystore.properties exists but is unusable:\n  - ${problems.joinToString("\n  - ")}\n" +
            "Fix the file, or delete it to fall back to the debug signing key.",
    )
}

// Set -PshieldRequireReleaseSigning=true in CI to make a debug-signed release
// a hard failure instead of a warning.
val requireReleaseSigning = providers.gradleProperty("shieldRequireReleaseSigning")
    .map { it.toBoolean() }
    .getOrElse(false)

val releaseRequested = gradle.startParameter.taskNames
    .any { it.contains("release", ignoreCase = true) }

if (!hasReleaseSigning) {
    val message = "Shield release is NOT production-signed: keystore.properties is absent, " +
        "so the debug key is being used. Run scripts/generate-keystore.ps1 " +
        "(or .sh) before publishing."
    // An explicit opt-in must fail the build no matter which task is running.
    if (requireReleaseSigning) {
        throw GradleException(message)
    }
    // Otherwise only warn, and only when a release task was actually requested,
    // so ordinary debug builds stay quiet.
    if (releaseRequested) {
        logger.warn("WARNING: $message")
    }
}

android {
    namespace = "dev.gamblock.shield"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.gamblock.shield"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Populated only when keystore.properties is present and valid. The
        // block is always created so the build can still be configured without
        // secrets; buildTypes below selects it only when hasReleaseSigning.
        create("release") {
            configuredStoreFile?.let { storeFile = it }
            storePassword = keystoreProperties.getProperty("storePassword", "")
            keyAlias = keystoreProperties.getProperty("keyAlias", "")
            keyPassword = keystoreProperties.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "REVIEWER_MODE_ENABLED", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "REVIEWER_MODE_ENABLED", "false")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:design-system"))
    implementation(project(":core:security"))
    implementation(project(":data:blocklist"))
    implementation(project(":data:preferences"))
    implementation(project(":data:repository"))
    implementation(project(":data:update"))
    implementation(project(":protection:vpn"))
    implementation(project(":protection:boot"))
    implementation(project(":protection:health"))
    implementation(project(":protection:oem"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:reports"))
    implementation(project(":feature:diagnostics"))
    implementation(project(":feature:support"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:accountability"))
    implementation(project(":feature:parent"))
    implementation(project(":data:accountability"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
}