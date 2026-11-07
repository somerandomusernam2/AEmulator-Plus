/* Added for AEmulator Aster (2026-11-07). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * The RSA handshake of a real adbd, so that network ADB is not an open root shell for everyone on the same Wi-Fi.
 *
 * The device sends a random token; the client signs it with its key (~/.android/adbkey) and the device checks the
 * signature against the keys it already trusts. A client none of them knows sends its public key instead, and the
 * person at the phone is asked, as on a real phone ("Allow USB debugging?"). Keys allowed with "always" are kept in
 * [file], one "<base64> <user@host>" per line like /data/misc/adb/adb_keys, and are shared by every firmware.
 */
class AdbAuth(private val file: File) {
    /** what the person at the phone said to a computer seen for the first time */
    enum class Answer { DENY, ONCE, ALWAYS }

    /** A client's public key as the adb client sends it (AUTH RSAPUBLICKEY): its own binary form in base64, then a comment. */
    class Key(val line: String, val key: PublicKey, val fingerprint: String) {
        val comment: String get() = line.substringAfter(' ', "").trim()
    }

    private val rnd = SecureRandom()
    @Volatile private var trusted: List<Key>? = null
    /** keys allowed for this run of the machine only ("always" unchecked) */
    private val session = java.util.concurrent.CopyOnWriteArrayList<Key>()

    fun token(): ByteArray = ByteArray(TOKEN_SIZE).also { rnd.nextBytes(it) }

    /** whether [sig] is [token] signed by a key that is allowed */
    fun verify(token: ByteArray, sig: ByteArray): Boolean = (keys() + session).any { verify(it.key, token, sig) }

    @Synchronized
    fun trust(k: Key, always: Boolean) {
        if (!always) { session.add(k); return }
        val cur = keys()
        if (cur.any { it.fingerprint == k.fingerprint }) return
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(k.line + "\n")
        }
        trusted = cur + k
    }

    /** forgets every computer allowed so far: each has to be allowed again on its next connection */
    @Synchronized
    fun revokeAll() {
        session.clear()
        runCatching { file.delete() }
        trusted = emptyList()
    }

    fun count(): Int = (keys() + session).distinctBy { it.fingerprint }.size

    private fun keys(): List<Key> = trusted ?: synchronized(this) {
        trusted ?: runCatching { file.readLines() }.getOrDefault(emptyList()).mapNotNull { parse(it) }.also { trusted = it }
    }

    companion object {
        const val TOKEN_SIZE = 20

        /** DigestInfo of SHA-1: the client signs the token as if it were a SHA-1 digest (RSA_sign(NID_sha1, …)) */
        private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

        fun verify(key: PublicKey, token: ByteArray, sig: ByteArray): Boolean = runCatching {
            Signature.getInstance("NONEwithRSA").run {
                initVerify(key)
                update(SHA1_PREFIX + token)
                verify(sig)
            }
        }.getOrDefault(false)

        /**
         * "<base64> [user@host]" (the payload may end in a NUL). The base64 is the client's RSAPublicKey struct:
         * modulus size in 32-bit words, n0inv, modulus (little-endian), R^2 mod n (little-endian), exponent.
         */
        fun parse(raw: String): Key? = runCatching {
            val line = raw.trimEnd('\u0000').trim()
            if (line.isEmpty()) return null
            val blob = Base64.getMimeDecoder().decode(line.substringBefore(' '))
            val b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val words = b.int
            if (words !in 32..256 || blob.size != 12 + words * 8) return null       // 1024 to 8192-bit keys
            b.int                                                                     // n0inv, only for the device's own math
            val n = ByteArray(words * 4).also { b.get(it) }.reversedArray()
            b.position(b.position() + words * 4)                                      // rr
            val e = b.int.toLong() and 0xffffffffL
            val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, n), BigInteger.valueOf(e)))
            // the fingerprint a real phone shows: MD5 of the key's binary form
            val fp = MessageDigest.getInstance("MD5").digest(blob).joinToString(":") { "%02X".format(it) }
            Key(line, key, fp)
        }.getOrNull()
    }
}
