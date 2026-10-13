package io.github.sumirenokai.vesqen.premium

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Checks activation codes offline (docs/ACTIVATION_CODES.md). The owner's tool in
 * `server/usage/src/activation-code.js` writes the same format; a shared test vector keeps the two
 * equal. A code is a 9-byte payload and a 64-byte ECDSA P-256 signature, in Crockford base32.
 */
internal class ActivationCodeVerifier(
    private val publicKeys: Map<Int, PublicKey>,
    /** (key id, serial) pairs revoked after a refund, chargeback or leak; shipped with each version. */
    private val revoked: Set<Pair<Int, Long>>,
) {
    sealed interface Outcome {
        /** [compact] is the code without separators, as kept and shown. */
        data class Genuine(val keyId: Int, val serial: Long, val compact: String) : Outcome
        data class Revoked(val keyId: Int, val serial: Long, val compact: String) : Outcome
        data object Malformed : Outcome
        data object NotGenuine : Outcome
    }

    fun check(text: String): Outcome {
        val compact = normalize(text) ?: return Outcome.Malformed
        val bytes = decode(compact) ?: return Outcome.Malformed
        if (bytes[0].toInt() != FORMAT_VERSION) return Outcome.Malformed
        val keyId = bytes[1].toInt() and 0xff
        if (bytes[2].toInt() != PRODUCT_PRO) return Outcome.NotGenuine
        val key = publicKeys[keyId] ?: return Outcome.NotGenuine
        val payload = bytes.copyOfRange(0, PAYLOAD_BYTES)
        val genuine = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(DOMAIN)
                update(payload)
                verify(rawToDer(bytes.copyOfRange(PAYLOAD_BYTES, CODE_BYTES)))
            }
        }.getOrDefault(false)
        if (!genuine) return Outcome.NotGenuine
        val serial = ((bytes[3].toLong() and 0xff) shl 24) or ((bytes[4].toLong() and 0xff) shl 16) or
            ((bytes[5].toLong() and 0xff) shl 8) or (bytes[6].toLong() and 0xff)
        return if ((keyId to serial) in revoked) Outcome.Revoked(keyId, serial, compact)
        else Outcome.Genuine(keyId, serial, compact)
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val PRODUCT_PRO = 1
        private const val PAYLOAD_BYTES = 9
        private const val CODE_BYTES = PAYLOAD_BYTES + 64
        private const val CODE_CHARS = (CODE_BYTES * 8 + 4) / 5
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private val DOMAIN = "vesqen.activation.v1\n".toByteArray(Charsets.UTF_8)

        /** Groups of five, as the website shows them. */
        fun display(compact: String): String = compact.chunked(5).joinToString("-")

        fun publicKey(spkiBase64: String): PublicKey =
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spkiBase64)))

        /** Revocation lines are `keyId:serial`; blank lines and `#` comments are skipped. */
        fun parseRevoked(lines: Sequence<String>): Set<Pair<Int, Long>> = lines
            .map { it.substringBefore('#').trim() }
            .filter(String::isNotEmpty)
            .map { line ->
                val (key, serial) = line.split(':').also { require(it.size == 2) { "Bad revocation line" } }
                key.trim().toInt() to serial.trim().toLong()
            }
            .toSet()

        /** People copy codes with spaces, dashes, line breaks or lowercase; Crockford reads O as 0 and I, L as 1. */
        private fun normalize(text: String): String? {
            val compact = buildString {
                text.forEach { char ->
                    when {
                        char.isWhitespace() || char == '-' -> Unit
                        else -> append(
                            when (val upper = char.uppercaseChar()) {
                                'O' -> '0'
                                'I', 'L' -> '1'
                                else -> upper
                            },
                        )
                    }
                }
            }
            return compact.takeIf { it.length == CODE_CHARS && it.all { char -> char in ALPHABET } }
        }

        private fun decode(compact: String): ByteArray? {
            val bytes = ByteArray(CODE_BYTES)
            var value = 0
            var bits = 0
            var index = 0
            compact.forEach { char ->
                value = (value shl 5) or ALPHABET.indexOf(char)
                bits += 5
                if (bits >= 8) {
                    bytes[index++] = (value ushr (bits - 8)).toByte()
                    bits -= 8
                }
                value = value and ((1 shl bits) - 1)
            }
            // The last character carries padding bits; a nonzero one means the text was altered.
            return bytes.takeIf { value == 0 }
        }

        /** The tool writes r || s (IEEE P1363); Java's verifier takes the DER sequence. */
        private fun rawToDer(raw: ByteArray): ByteArray {
            fun integer(part: ByteArray): ByteArray {
                var start = 0
                while (start < part.size - 1 && part[start] == 0.toByte()) start++
                val trimmed = part.copyOfRange(start, part.size)
                val body = if (trimmed[0].toInt() and 0x80 != 0) byteArrayOf(0) + trimmed else trimmed
                return byteArrayOf(0x02, body.size.toByte()) + body
            }
            val r = integer(raw.copyOfRange(0, 32))
            val s = integer(raw.copyOfRange(32, 64))
            return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
        }
    }
}
