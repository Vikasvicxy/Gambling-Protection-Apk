import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("dev.gamblock.tools.blocklist.Main")
}

val projectDir = layout.projectDirectory

/**
 * Bootstraps a local DEV signing keypair. Only the PUBLIC key is placed in
 * `data:update` assets (shipped in the APK). The private key is written under
 * `local/` which is git-ignored - the true release key must come from CI secrets and
 * is never checked in. Safe to re-run: the committed public key is authoritative and
 * is NEVER overwritten, so CI can never diverge the shipped verifier from the signing key.
 */
tasks.register("generateDevSigningKeys") {
    group = "shield release"
    description = "Generates a dev ECDSA P-256 signing keypair (public to assets, private under local/)."
    val publicTarget = rootProject.layout.projectDirectory
        .dir("data").dir("update").dir("src").dir("main").dir("assets")
        .file("update_signing_public_key.pem")
    val privateTarget = rootProject.layout.projectDirectory.dir("local").file("dev-signing-private-key.pem")
    val public = publicTarget.asFile
    val private = privateTarget.asFile
    outputs.file(public)
    doLast {
        val force = (project.findProperty("shield.forceKeys") as? String) == "true"
        if (public.exists() && !force) {
            logger.lifecycle(
                if (private.exists()) "signing keys present; regenerating private key only under local/"
                else "public signing key present (authoritative); keeping it - refresh private key under local/",
            )
            // Public key is the committed verifier; never regenerate it. If a CI secret
            // provides the private key it is used directly. Local dev users must keep the
            // matching private key under local/.
            if (!private.exists()) logger.warn("set local/dev-signing-private-key.pem (dev) or configure RELEASE_SIGNING_PRIVATE_KEY in CI")
            return@doLast
        }
        public.parentFile.mkdirs()
        private.parentFile.mkdirs()
        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        public.writeText(pem("PUBLIC KEY", kp.public.encoded))
        private.writeText(pem("PRIVATE KEY", kp.private.encoded))
        logger.lifecycle("dev signing keys written: ${public} + ${private}")
    }
}

private fun pem(label: String, der: ByteArray): String {
    val body = Base64.getEncoder().encodeToString(der)
        .chunked(64).joinToString("\n")
    return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
}

tasks.named("compileKotlin") {
    dependsOn("generateDevSigningKeys")
}

/**
 * JVM micro-benchmarks for the domain index and signed release pipeline
 * (see BenchmarkMain.kt; results feed docs/PERFORMANCE.md).
 */
val benchmark by tasks.registering(JavaExec::class) {
    group = "shield release"
    description = "Runs the JVM micro-benchmarks (domain trie + signed release pipeline)."
    mainClass.set("dev.gamblock.tools.blocklist.BenchmarkMain")
    classpath = sourceSets.main.get().runtimeClasspath
}

dependencies {
    implementation(project(":core:release"))
    implementation(project(":core:model"))
    implementation(project(":protection:domain-engine"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}