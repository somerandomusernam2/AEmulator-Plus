package app.aemu.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tiny dex with KeyguardServiceWrapper.isSecure()Z whose body is `const/4 v0, #1; return v0`. */
class KeyguardSecurePatchTest {
    private fun mutf(s: String): ByteArray = ByteArrayOutputStream().apply { write(s.length); write(s.toByteArray(Charsets.UTF_8)); write(0) }.toByteArray()

    
    private fun dex(): ByteArray {
        val strings = listOf("Z", "Lcom/android/internal/policy/impl/keyguard/KeyguardServiceWrapper;", "isSecure")
        val hdr = 0x70
        val stringIdsOff = hdr
        val typeIdsOff = stringIdsOff + 4 * strings.size
        val protoIdsOff = typeIdsOff + 4 * 2
        val methodIdsOff = protoIdsOff + 12
        val classDefsOff = methodIdsOff + 8
        val dataOff = classDefsOff + 32
        val strData = strings.map { mutf(it) }
        val strOffs = ArrayList<Int>(); var o = dataOff
        for (sd in strData) { strOffs += o; o += sd.size }
        val classDataOff = o
        val classDataLen = 8                       // 4 sizes, then one method: diff, access, code_off (two uleb bytes)
        val codeOff = (classDataOff + classDataLen + 3) and 3.inv()
        require(codeOff < 1 shl 14)
        val total = codeOff + 20
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.put("dex\n035\u0000".toByteArray(Charsets.US_ASCII))
        b.putInt(32, total); b.putInt(36, hdr); b.putInt(40, 0x12345678)
        b.putInt(56, strings.size); b.putInt(60, stringIdsOff); b.putInt(64, 2); b.putInt(68, typeIdsOff); b.putInt(72, 1); b.putInt(76, protoIdsOff)
        b.putInt(88, 1); b.putInt(92, methodIdsOff); b.putInt(96, 1); b.putInt(100, classDefsOff); b.putInt(104, total - dataOff); b.putInt(108, dataOff)
        for ((i, so) in strOffs.withIndex()) b.putInt(stringIdsOff + 4 * i, so)
        b.putInt(typeIdsOff, 1); b.putInt(typeIdsOff + 4, 0)                 // type 0 = Watchdog (string 1), type 1 = V (string 0)
        b.putInt(protoIdsOff, 0); b.putInt(protoIdsOff + 4, 1); b.putInt(protoIdsOff + 8, 0)   // shorty "V", returns type 1 (V)
        b.putShort(methodIdsOff, 0); b.putShort(methodIdsOff + 2, 0); b.putInt(methodIdsOff + 4, 2)   // class type 0, proto 0, name "run"
        b.putInt(classDefsOff, 0); b.putInt(classDefsOff + 4, 1); b.putInt(classDefsOff + 8, -1); b.putInt(classDefsOff + 16, -1); b.putInt(classDefsOff + 24, classDataOff)
        var p = dataOff; for (sd in strData) { b.position(p); b.put(sd); p += sd.size }
        b.position(classDataOff)
        b.put(0); b.put(0); b.put(0); b.put(1)                                // static, instance, direct, virtual counts
        b.put(0); b.put(1); b.put((codeOff and 0x7f or 0x80).toByte()); b.put((codeOff ushr 7).toByte())
        b.putShort(codeOff, 1); b.putShort(codeOff + 2, 1); b.putShort(codeOff + 4, 0); b.putShort(codeOff + 6, 0); b.putInt(codeOff + 8, 0)
        b.putInt(codeOff + 12, 2)                                             // insns_size: const/4 v0, #0 ; return-void
        b.put(codeOff + 16, 0x12); b.put(codeOff + 17, 0x10); b.put(codeOff + 18, 0x0f); b.put(codeOff + 19, 0)   // const/4 v0, #1 ; return v0
        val d = b.array()
        DexTools.fixHeader(d)
        return d
    }


    private fun insns(d: ByteArray): Int {
        val x = DexTools.Dex(d)
        return DexTools.methodCode(x, "isSecure", KeyguardSecurePatch.CLASS_DESC, returns = "Z")!![0] + 16
    }

    @Test fun isSecureNowReturnsFalse() {
        val d = dex()
        assertEquals(WatchdogPatch.Outcome.PATCHED, KeyguardSecurePatch.patchDex(d).outcome)
        val p = insns(d)
        assertEquals(listOf<Byte>(0x12, 0, 0x0f, 0), listOf(d[p], d[p + 1], d[p + 2], d[p + 3]))
    }

    @Test fun secondRunIsAlready() {
        val d = dex()
        KeyguardSecurePatch.patchDex(d)
        assertEquals(WatchdogPatch.Outcome.ALREADY, KeyguardSecurePatch.patchDex(d).outcome)
    }

    @Test fun otherFilesAreAbsent() =
        assertEquals(WatchdogPatch.Outcome.ABSENT, KeyguardSecurePatch.patchDex(ByteArray(300)).outcome)
}
