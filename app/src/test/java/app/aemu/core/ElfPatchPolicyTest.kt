package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

/** Instruction words of the Samsung Froyo AudioPolicyService() around the ro.kernel.qemu check (virtual address 0x2ea14). */
class ElfPatchPolicyTest {
    private val base = 0x2ea14L
    private val factory = 0x15f20L
    private val ctorTail = intArrayOf(
        0xE59F109C.toInt(), 0xE28D6028.toInt(), 0xE3A02000.toInt(), 0xE0850001.toInt(), 0xE1A01006.toInt(),
        0xEBFF9E8C.toInt(), // bl property_get
        0xE3500000.toInt(), // cmp r0, #0
        0x0A000015,         // beq create
        0xE59F7080.toInt(), 0xE1A01006.toInt(), 0xE59F007C.toInt(), 0xE0857007.toInt(), 0xE0852000.toInt(),
        0xE1A00007.toInt(), 0xEBFF9E83.toInt(), 0xE594C01C.toInt(), 0xE1A02006.toInt(), 0xE1A01007.toInt(),
        0xE1A0000C.toInt(), 0xE59C3000.toInt(), 0xE1A0E00F.toInt(), 0xE593F020.toInt(), 0xE795C009.toInt(),
        0xE59D2084.toInt(), 0xE1A00004.toInt(), 0xE59C3000.toInt(), 0xE1520003.toInt(), 0x1A000005,
        0xE28DD08C.toInt(), 0xE8BD8FF0.toInt(),
        0xE2840010.toInt(), // create: add r0, r4, #16
        0xEBFF9D22.toInt(), // bl createAudioPolicyManager
        0xE584001C.toInt(), // str r0, [r4, #28]
        0xEAFFFFE5.toInt(), 0xEBFF9E63.toInt()
    )

    @Test fun findsTheEmulatorBranch() {
        assertEquals(7, ElfPatch.policyBranchIn(ctorTail, base) { it == factory })
    }

    @Test fun ignoresAlreadyPatchedCode() {
        val patched = ctorTail.copyOf().also { it[7] = 0xEA000015.toInt() }
        assertNull(ElfPatch.policyBranchIn(patched, base) { it == factory })
    }

    @Test fun needsTheFactoryCall() {
        assertNull(ElfPatch.policyBranchIn(ctorTail, base) { false })
    }

    @Test fun needsTheCompareBeforeTheBranch() {
        val other = ctorTail.copyOf().also { it[6] = 0xE1A00000.toInt() } // nop instead of cmp r0, #0
        assertNull(ElfPatch.policyBranchIn(other, base) { it == factory })
    }

    @Test fun branchMustLandOnTheFactoryCall() {
        val other = ctorTail.copyOf().also { it[7] = 0x0A000005 } // beq somewhere else
        assertNull(ElfPatch.policyBranchIn(other, base) { it == factory })
    }

    @Test fun rejectsNonElf() {
        assertTrue(ElfPatch.policyBranchOffsets(ByteArray(0)).isEmpty())
        assertTrue(ElfPatch.policyBranchOffsets(ByteArray(4096)).isEmpty())
    }
}
