/* Added for AEmulator Aster (2026-11-07). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class AdbAuthTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rnd = SecureRandom()

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** the line an adb client sends (AUTH RSAPUBLICKEY): android_pubkey_encode() in base64, a comment, a NUL */
    private fun adbPublicKey(kp: KeyPair, comment: String = "user@pc"): String {
        val pub = kp.public as RSAPublicKey
        val n = pub.modulus
        val words = 64
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = two32.subtract(n.mod(two32).modInverse(two32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(2048).pow(2).mod(n)
        fun le(v: BigInteger): ByteArray {
            val be = v.toByteArray().let { if (it.size > 256) it.copyOfRange(it.size - 256, it.size) else it }
            return (ByteArray(256 - be.size) + be).reversedArray()
        }
        val b = ByteBuffer.allocate(12 + words * 8).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words).putInt(n0inv).put(le(n)).put(le(rr)).putInt(pub.publicExponent.toInt())
        return Base64.getEncoder().encodeToString(b.array()) + " " + comment + "\u0000"
    }

    /** what the adb client does with the token: RSA_sign(NID_sha1, token, …), done here by hand (PKCS#1 v1.5 type 1) */
    private fun sign(kp: KeyPair, token: ByteArray): ByteArray {
        val prefix = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        val t = prefix + token
        val em = ByteArray(256)
        em[0] = 0; em[1] = 1
        for (i in 2 until 256 - t.size - 1) em[i] = 0xff.toByte()
        em[256 - t.size - 1] = 0
        System.arraycopy(t, 0, em, 256 - t.size, t.size)
        val priv = kp.private as RSAPrivateKey
        val s = BigInteger(1, em).modPow(priv.privateExponent, priv.modulus).toByteArray()
        return if (s.size > 256) s.copyOfRange(s.size - 256, s.size) else ByteArray(256 - s.size) + s
    }

    @Test fun parsesTheClientsKey() {
        val kp = keyPair()
        val k = AdbAuth.parse(adbPublicKey(kp))
        assertNotNull(k)
        assertEquals((kp.public as RSAPublicKey).modulus, (k!!.key as RSAPublicKey).modulus)
        assertEquals(BigInteger.valueOf(65537), (k.key as RSAPublicKey).publicExponent)
        assertEquals("user@pc", k.comment)
        assertEquals(47, k.fingerprint.length)                       // 16 bytes of MD5 as XX:XX:…
    }

    @Test fun rejectsGarbage() {
        assertNull(AdbAuth.parse(""))
        assertNull(AdbAuth.parse("not base64 at all!"))
        assertNull(AdbAuth.parse(Base64.getEncoder().encodeToString(ByteArray(100)) + " x"))
    }

    @Test fun signatureOfTheRightKeyOnly() {
        val kp = keyPair()
        val other = keyPair()
        val k = AdbAuth.parse(adbPublicKey(kp))!!
        val token = ByteArray(AdbAuth.TOKEN_SIZE).also { rnd.nextBytes(it) }
        assertTrue(AdbAuth.verify(k.key, token, sign(kp, token)))
        assertFalse(AdbAuth.verify(k.key, token, sign(other, token)))
        val wrong = token.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(AdbAuth.verify(k.key, wrong, sign(kp, token)))
        assertFalse(AdbAuth.verify(k.key, token, ByteArray(256)))
    }

    @Test fun alwaysIsKeptOnceIsNot() {
        val file = File(tmp.root, "adb_keys")
        val a = keyPair(); val b = keyPair()
        val auth = AdbAuth(file)
        val token = auth.token()
        assertFalse(auth.verify(token, sign(a, token)))

        auth.trust(AdbAuth.parse(adbPublicKey(a, "a@pc"))!!, always = true)
        auth.trust(AdbAuth.parse(adbPublicKey(b, "b@pc"))!!, always = false)
        assertTrue(auth.verify(token, sign(a, token)))
        assertTrue(auth.verify(token, sign(b, token)))
        assertEquals(2, auth.count())

        // a new run of the machine: only the remembered computer
        val again = AdbAuth(file)
        assertTrue(again.verify(token, sign(a, token)))
        assertFalse(again.verify(token, sign(b, token)))
        assertEquals(1, file.readLines().count { it.isNotBlank() })

        // the same key twice is stored once
        again.trust(AdbAuth.parse(adbPublicKey(a, "a@pc"))!!, always = true)
        assertEquals(1, file.readLines().count { it.isNotBlank() })

        again.revokeAll()
        assertFalse(again.verify(token, sign(a, token)))
        assertFalse(AdbAuth(file).verify(token, sign(a, token)))
        assertEquals(0, again.count())
    }
}
