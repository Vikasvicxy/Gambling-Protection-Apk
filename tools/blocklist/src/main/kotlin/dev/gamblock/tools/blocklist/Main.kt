package dev.gamblock.tools.blocklist

import dev.gamblock.core.release.BuiltRelease
import dev.gamblock.core.release.PayloadCodec
import dev.gamblock.core.release.ReleaseBuilder
import dev.gamblock.core.release.ReleaseCrypto
import dev.gamblock.core.release.ReleasePipeline
import dev.gamblock.core.release.ReleaseVerifier
import dev.gamblock.core.release.ReleaseValidator
import dev.gamblock.core.release.RawSourceEntry
import dev.gamblock.core.release.SourceDefinition
import dev.gamblock.core.release.SourceFormat
import dev.gamblock.core.release.SourceDefinitions
import dev.gamblock.core.release.TrustedKeyRing
import dev.gamblock.core.release.UpdateChannel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.security.KeyPair
import kotlinx.serialization.json.Json

/**
 * Serverless release pipeline (CI / maintainer command, never shipped in the APK).
 *
 *   fetch     -> mirror the three licensed sources under out/sources/
 *   process   -> normalize, dedupe, classify, allowlist-safety, write out/processed.ndjson.gz
 *   build     -> diff against the previous release, sign, emit out/releases/ (full + delta + manifest)
 *   verify    -> re-verify the newest signed release with the public key (self-check)
 *
 * The private signing key is read from the git-ignored local/ directory (or --private-key)
 * and is never part of this repository.
 */
object Main {

    val json = Json { ignoreUnknownKeys = false; encodeDefaults = false }

    @JvmStatic
    fun main(args: Array<String>) {
        val cmd = args.firstOrNull() ?: run {
            printUsage()
            return
        }
        val root = repoRoot(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath())
        when (cmd) {
            "fetch" -> fetchAll(root)
            "process" -> processAll(root)
            "build" -> buildRelease(root, args)
            "verify" -> verifyLatest(root)
            "--help" -> printUsage()
            else -> {
                println("unknown command: $cmd")
                printUsage()
            }
        }
    }

    private fun printUsage() {
        println(
            """
            |shield-blocklist release pipeline
            |  fetch   - download licensed sources under out/sources/
            |  process - build out/processed.ndjson.gz from out/sources/*
            |  build   - sign + emit out/releases/ (full + delta + manifest.json)
            |            [--public-key <pem>] [--private-key <pem>] [--version <int>]
            |            [--channel canon|canary|stable] [--release-id <string>]
            |  verify  - self-check the newest release with the public key
            """.trimMargin(),
        )
    }

    // ------------------------------------------------------------------ fetch

    private fun fetchAll(root: Path) {
        val out = (root / "out" / "sources").toFile().apply { mkdirs() }
        for (definition in SourceDefinitions.CONFIGURED) {
            val raw = Http.fetch(definition.url, maxBytes = 64L * 1024L * 1024L)
            val file = out.resolve("${definition.id}.raw")
            file.writeBytes(raw)
            println("fetched ${definition.id} (${raw.size} bytes) license ${definition.license}")
        }
    }

    // ------------------------------------------------------------------ process

    private fun processAll(root: Path) {
        val sources = (root / "out" / "sources").toFile()
        val sourceDir = sources
        val entries = mutableListOf<RawSourceEntry>()
        for (definition in SourceDefinitions.CONFIGURED) {
            val file = sourceDir.resolve("${definition.id}.raw")
            if (!file.exists()) {
                println("missing source file ${file.name}; run 'fetch' first")
                kotlin.system.exitProcess(2)
            }
            entries += parseRaw(definition, file.readText())
        }

        ReleasePipeline.validateSourceLicenses(SourceDefinitions.CONFIGURED)
        val ingested = ReleasePipeline.process(
            entries = entries,
            definitions = SourceDefinitions.CONFIGURED,
            nowEpochMs = System.currentTimeMillis(),
            databaseVersion = 1050, // replaced with the real version at build time
        )
        val records = ReleasePipeline.toReleaseRecords(ingested)
        ReleaseValidator.validateRecords(records)
        val compressed = PayloadCodec.encodeRecords(records)
        (root / "out").toFile().mkdirs()
        val output = (root / "out" / "processed.ndjson.gz").toFile()
        output.writeBytes(compressed)
        println("processed ${records.size} unique domains -> ${output.name} (${compressed.size} bytes)")
        val counts = records.groupingBy { it.status }.eachCount()
        println("by status: $counts")
    }

    // ------------------------------------------------------------------ build

    private fun buildRelease(root: Path, args: Array<String>) {
        val flags = args.drop(1).chunked(2).associate { it[0] to it.getOrElse(1) { "" } }
        val publicFile = flags["--public-key"]?.takeIf { it.isNotBlank() }
            ?: (root / "data" / "update" / "src" / "main" / "assets" / "update_signing_public_key.pem").toString()
        val privateFile = flags["--private-key"]?.takeIf { it.isNotBlank() }
            ?: (root / "local" / "dev-signing-private-key.pem").toString()
        val version = flags["--version"]?.toIntOrNull() ?: nextVersion(root)
        val channel = when (flags["--channel"]) {
            "canary" -> UpdateChannel.CANARY
            "internal" -> UpdateChannel.INTERNAL
            else -> UpdateChannel.STABLE
        }

        val publicKey = File(publicFile).takeIf { it.exists() }?.readText()?.let { ReleaseCrypto.parsePublicKeyPem(it) }
            ?: error("public key not found at $publicFile (run :tools:blocklist:generateDevSigningKeys)")
        val privateKey = File(privateFile).takeIf { it.exists() }?.readText()?.let { ReleaseCrypto.parsePrivateKeyPem(it) }
            ?: error("private key not found at $privateFile (never committed; CI secrets/local only)")

        val processed = File((root / "out" / "processed.ndjson.gz").toString()).readBytes()
        val nextRecords = PayloadCodec.decodeRecords(processed)
            .map { if (it.databaseVersion == version) it else it.copy(databaseVersion = version) }
        ReleaseValidator.validateRecords(nextRecords)

        val previous = loadPrevious(root)
        val now = System.currentTimeMillis()
        val releaseId = flags["--release-id"]?.takeIf { it.isNotBlank() }
            ?: "release-${channel.name.lowercase()}-${java.time.Instant.ofEpochMilli(now).toString().take(10)}"
        val built = ReleaseBuilder.build(
            previousRecords = previous,
            nextRecords = nextRecords,
            releaseId = releaseId,
            version = version,
            channel = channel,
            generatedAtEpochMs = now,
            minimumAppVersion = 1,
            signingKey = KeyPair(publicKey, privateKey),
            changelog = "automatic signed release",
        )
        writeRelease(root, built, version)
        println("built+signed release v$version (${built.domainCount} domains) keyId ${built.signingPublicKey.let { ReleaseCrypto.fingerprint(it) }}")
    }

    private fun writeRelease(root: Path, built: BuiltRelease, version: Int) {
        val dir = (root / "out" / "releases").toFile().apply { mkdirs() }
        val envelope = ReleaseVerifier.encodeEnvelope(built.envelope)
        dir.resolve("manifest-v$version.json").writeText(envelope)
        dir.resolve(built.envelope.manifest.full.fileName).writeBytes(built.fullPayload)
        built.deltaPayload?.let { dir.resolve(built.envelope.manifest.delta!!.fileName).writeBytes(it) }
        dir.resolve("latest.json").writeText(envelope)
        println("artifacts in ${dir.absolutePath}")
    }

    private fun loadPrevious(root: Path): List<dev.gamblock.core.release.ReleaseDomainRecord>? {
        val latest = (root / "out" / "releases" / "latest.json").toFile()
        if (!latest.exists()) return null
        val envelope = ReleaseVerifier.parseEnvelope(latest.readText())
        val payload = File((root / "out" / "releases" / envelope.manifest.full.fileName).toString()).readBytes()
        return try {
            PayloadCodec.decodeRecords(payload)
        } catch (e: Exception) {
            null
        }
    }

    private fun nextVersion(root: Path): Int {
        val latest = (root / "out" / "releases" / "latest.json").toFile()
        if (!latest.exists()) return 1050
        val envelope = ReleaseVerifier.parseEnvelope(latest.readText())
        return envelope.manifest.version + 1
    }

    // ------------------------------------------------------------------ verify

    private fun verifyLatest(root: Path) {
        val latest = (root / "out" / "releases" / "latest.json").toFile()
        if (!latest.exists()) error("no release yet; run build")
        val publicFile = root / "data" / "update" / "src" / "main" / "assets" / "update_signing_public_key.pem"
        val publicKey = ReleaseCrypto.parsePublicKeyPem(publicFile.toFile().readText())
        val ring = TrustedKeyRing(listOf(publicKey))
        val envelope = ReleaseVerifier.parseEnvelope(latest.readText())
        val payload = File((root / "out" / "releases" / envelope.manifest.full.fileName).toString()).readBytes()
        val result = ReleaseVerifier.verifyFullEnvelope(
            envelope,
            payload,
            ring,
            installedVersion = envelope.manifest.version - 1,
            currentAppVersionCode = 1,
            maxObservedVersion = envelope.manifest.version - 1,
        )
        when {
            result.ok ->
                println("VERIFY OK: release ${envelope.manifest.releaseId} v${envelope.manifest.version} signature+hash+payload valid")
            else ->
                error("VERIFY FAILED: ${result.reason}")
        }
    }

    /** Lazy host-style parsing of a source file into raw entries. */
    fun parseRaw(definition: SourceDefinition, text: String): List<RawSourceEntry> {
        val entries = ArrayList<RawSourceEntry>()
        if (definition.format == SourceFormat.HOSTS) {
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size < 2) continue
                if (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1") {
                    entries.add(RawSourceEntry(definition.id, parts[1]))
                }
            }
        } else {
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                entries.add(RawSourceEntry(definition.id, trimmed))
            }
        }
        return entries
    }

    private operator fun Path.div(child: String): Path = resolve(child)

    /**
     * Walks up from the working directory until it finds the Shield repo root
     * (marked by `settings.gradle.kts`). Falls back to the working dir so the CLI
     * is safe to run from any module or the repo root.
     */
    private fun repoRoot(start: Path): Path {
        var dir: Path? = start
        while (dir != null) {
            if (dir.resolve("settings.gradle.kts").toFile().exists()) return dir
            if (dir.resolve("settings.gradle").toFile().exists()) return dir
            dir = dir.parent
        }
        return start
    }

    // ------------------------------------------------------------------ http

    private object Http {
        fun fetch(url: String, maxBytes: Long): ByteArray {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "shield-release-builder/1")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) error("http $code fetching $url")
                val out = java.io.ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) error("stream from $url exceeds cap")
                        out.write(buffer, 0, read)
                    }
                }
                return out.toByteArray()
            } finally {
                conn.disconnect()
            }
        }
    }
}