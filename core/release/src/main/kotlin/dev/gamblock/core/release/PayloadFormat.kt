package dev.gamblock.core.release

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.Json

/**
 * On-wire payload formats.
 *
 * Full payload: NDJSON (one [ReleaseDomainRecord] per line), gzip-compressed.
 * Delta payload: gzip-compressed NDJSON of [DeltaOp] lines: a header line followed by
 * add/remove/modify operations.
 *
 * Both formats are streaming-friendly, deterministic, diff-able on disk (gzip -d),
 * and immune to archive path-traversal / zip-bomb attacks by construction (no archive
 * entries; plain byte streams converted with a hard decompressed-size limit).
 */
object PayloadCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = false
    }

    const val MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L // 512 MiB safety ceiling.

    fun encodeRecords(records: List<ReleaseDomainRecord>): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).bufferedWriter(Charsets.UTF_8).use { writer ->
            for (record in records) {
                writer.append(json.encodeToString(ReleaseDomainRecord.serializer(), record))
                writer.append('\n')
            }
        }
        return bos.toByteArray()
    }

    /**
     * Decodes a gzip NDJSON payload enforcing a hard decompression limit.
     * Throws [PayloadException] on truncation, bad gzip, or oversize output.
     */
    fun decodeRecords(
        compressed: ByteArray,
        maxUncompressedBytes: Long = MAX_UNCOMPRESSED_BYTES,
    ): List<ReleaseDomainRecord> {
        val text = decompress(compressed, maxUncompressedBytes)
        val records = ArrayList<ReleaseDomainRecord>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            try {
                records.add(json.decodeFromString(ReleaseDomainRecord.serializer(), trimmed))
            } catch (e: Exception) {
                throw PayloadException("malformed payload line: ${e.message}")
            }
        }
        return records
    }

    /** Low-guard variant used when validating deltas during a size-sensitive ingest. */
    fun decompress(compressed: ByteArray, maxUncompressedBytes: Long): String {
        try {
            val out = ByteArrayOutputStream()
            GZIPInputStream(ByteArrayInputStream(compressed)).use { gz ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = gz.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxUncompressedBytes) {
                        throw PayloadException(
                            "decompressed payload exceeds limit ($total > $maxUncompressedBytes bytes)",
                        )
                    }
                    out.write(buffer, 0, read)
                }
            }
            return out.toString(Charsets.UTF_8)
        } catch (e: IOException) {
            throw PayloadException("corrupted compressed payload: ${e.message}")
        }
    }
}

/** A single delta operation line. Discriminator is the wire `op` field (lowercase). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("op")
sealed class DeltaOp {

    @Serializable
    @SerialName("header")
    data class Header(
        val base: Int,
        val target: Int,
        val added: Int,
        val removed: Int,
        val modified: Int,
    ) : DeltaOp()

    @Serializable
    @SerialName("add")
    data class Add(
        val record: ReleaseDomainRecord,
    ) : DeltaOp()

    @Serializable
    @SerialName("modify")
    data class Modify(
        val record: ReleaseDomainRecord,
    ) : DeltaOp()

    @Serializable
    @SerialName("remove")
    data class Remove(
        val domain: String,
    ) : DeltaOp()
}

object DeltaCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = false
    }

    fun encode(ops: List<DeltaOp>): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).bufferedWriter(Charsets.UTF_8).use { writer ->
            for (op in ops) {
                writer.append(json.encodeToString(DeltaOp.serializer(), op))
                writer.append('\n')
            }
        }
        return bos.toByteArray()
    }

    fun decode(compressed: ByteArray, maxUncompressedBytes: Long = PayloadCodec.MAX_UNCOMPRESSED_BYTES): List<DeltaOp> {
        val text = PayloadCodec.decompress(compressed, maxUncompressedBytes)
        val ops = ArrayList<DeltaOp>()
        var sawHeader = false
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val op = try {
                json.decodeFromString(DeltaOp.serializer(), trimmed)
            } catch (e: Exception) {
                throw PayloadException("malformed delta line: ${e.message}")
            }
            when (op) {
                is DeltaOp.Header -> {
                    if (sawHeader) throw PayloadException("delta contains more than one header")
                    sawHeader = true
                }
                else -> if (!sawHeader) throw PayloadException("delta op before header")
            }
            ops.add(op)
        }
        if (!sawHeader) throw PayloadException("delta missing header")
        return ops
    }
}

class PayloadException(message: String, cause: Throwable? = null) : Exception(message, cause)