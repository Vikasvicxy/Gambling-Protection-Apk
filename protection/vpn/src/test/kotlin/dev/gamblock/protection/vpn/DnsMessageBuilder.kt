package dev.gamblock.protection.vpn

import java.io.ByteArrayOutputStream

/** Builds real DNS wire-format messages so the DoH layer is tested against realistic bytes. */
object DnsMessageBuilder {

    /** Standard query, recursion desired. */
    fun query(
        name: String,
        type: Int = TYPE_A,
        transactionId: Int = 0x1234,
        recursionDesired: Boolean = true,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(
            ((transactionId shr 8) and 0xFF).toByte(),
            (transactionId and 0xFF).toByte(),
        ))
        val flags = if (recursionDesired) 0x0100 else 0x0000
        out.write(byteArrayOf(((flags shr 8) and 0xFF).toByte(), (flags and 0xFF).toByte()))
        out.write(byteArrayOf(0x00, 0x01)) // QDCOUNT
        out.write(byteArrayOf(0x00, 0x00)) // ANCOUNT
        out.write(byteArrayOf(0x00, 0x00)) // NSCOUNT
        out.write(byteArrayOf(0x00, 0x00)) // ARCOUNT
        name.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(byteArrayOf(bytes.size.toByte()))
            out.write(bytes)
        }
        out.write(byteArrayOf(0x00)) // root label
        out.write(byteArrayOf(((type shr 8) and 0xFF).toByte(), (type and 0xFF).toByte()))
        out.write(byteArrayOf(0x00, 0x01)) // QCLASS IN
        return out.toByteArray()
    }

    /**
     * Builds a response echoing [question] with a single A record.
     */
    fun response(
        question: ByteArray,
        transactionId: Int = ((question[0].toInt() and 0xFF) shl 8) or (question[1].toInt() and 0xFF),
        rcode: Int = 0,
        answerAddress: ByteArray = byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(((transactionId shr 8) and 0xFF).toByte(), (transactionId and 0xFF).toByte()))
        // QR=1, RD=1, RA=1, RCODE
        val flags = 0x8180 or (rcode and 0x0F)
        out.write(byteArrayOf(((flags shr 8) and 0xFF).toByte(), (flags and 0xFF).toByte()))
        out.write(byteArrayOf(0x00, 0x01)) // QDCOUNT
        out.write(byteArrayOf(0x00, 0x01)) // ANCOUNT
        out.write(byteArrayOf(0x00, 0x00))
        out.write(byteArrayOf(0x00, 0x00))
        out.write(question.copyOfRange(12, question.size)) // echoed question
        // Answer: pointer to the question name, type A, class IN, TTL, rdlength, rdata
        out.write(byteArrayOf(0xC0.toByte(), 0x0C))
        out.write(byteArrayOf(0x00, TYPE_A.toByte()))
        out.write(byteArrayOf(0x00, 0x01))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x3C)) // TTL 60
        out.write(byteArrayOf(0x00, 0x04))
        out.write(answerAddress)
        return out.toByteArray()
    }

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_CNAME = 5
}