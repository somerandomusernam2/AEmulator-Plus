/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * ★ PART OF A MODIFIED QEMU. This file was ADDED to the QEMU sources
 * (linux-user/i386) by the ISTRATII_TECH SENSES project and replaces the
 * stock linux-user/i386/target_proc.h when qemu-i386 is built. It is
 * distributed under the GNU General Public License version 2 or (at your
 * option) any later version. Full text: COPYING. Everything changed in QEMU is
 * listed in NOTICE.
 *
 * Copyright (c) 2026 istratiitech <https://github.com/istratiitech>
 * Changed: 2026-10-22
 *
 * /proc/cpuinfo for an x86-32 guest.
 *
 * QEMU 8.2 has no /proc/cpuinfo emulation for i386, so an x86 guest read the
 * PHONE's file: an ARM one ("Processor : AArch64", "Features : fp asimd ...",
 * no "flags" line at all) with the host's core count. Native code that parses
 * it (core counting, "flags" probing) then sees nonsense. This answers with an
 * x86 file instead.
 *
 * The number of cores is GUEST_CPUS (default 4, 1..64), the same variable the
 * ARM build uses (patch 0055), not the phone's core count.
 *
 * The flag list is deliberately the conservative baseline every TCG x86 CPU
 * model has (a Pentium 4 with SSE3): code that trusts /proc/cpuinfo must never
 * be told about an instruction the translator would reject. A guest that
 * really needs more reads CPUID, which is exact. GUEST_X86_FLAGS replaces the
 * list, GUEST_X86_MODEL the model name.
 */
#ifndef I386_TARGET_PROC_H
#define I386_TARGET_PROC_H

static int open_cpuinfo(CPUArchState *cpu_env, int fd)
{
    const char *s = getenv("GUEST_CPUS");
    const char *flags = getenv("GUEST_X86_FLAGS");
    const char *model = getenv("GUEST_X86_MODEL");
    int num_cpus = (s && *s) ? atoi(s) : 4;

    if (num_cpus < 1) {
        num_cpus = 1;
    }
    if (num_cpus > 64) {
        num_cpus = 64;
    }
    if (!flags || !*flags) {
        flags = "fpu de pse tsc msr pae mce cx8 apic sep mtrr pge mca cmov "
                "pat pse36 clflush mmx fxsr sse sse2 pni";
    }
    if (!model || !*model) {
        model = "Intel(R) Atom(TM) CPU N270   @ 1.60GHz";
    }

    for (int i = 0; i < num_cpus; i++) {
        dprintf(fd,
                "processor\t: %d\n"
                "vendor_id\t: GenuineIntel\n"
                "cpu family\t: 6\n"
                "model\t\t: 28\n"
                "model name\t: %s\n"
                "stepping\t: 2\n"
                "cpu MHz\t\t: 1600.000\n"
                "cache size\t: 512 KB\n"
                "physical id\t: 0\n"
                "siblings\t: %d\n"
                "core id\t\t: %d\n"
                "cpu cores\t: %d\n"
                "apicid\t\t: %d\n"
                "initial apicid\t: %d\n"
                "fdiv_bug\t: no\n"
                "hlt_bug\t\t: no\n"
                "f00f_bug\t: no\n"
                "coma_bug\t: no\n"
                "fpu\t\t: yes\n"
                "fpu_exception\t: yes\n"
                "cpuid level\t: 10\n"
                "wp\t\t: yes\n"
                "flags\t\t: %s\n"
                "bogomips\t: 3200.00\n"
                "clflush size\t: 64\n"
                "cache_alignment\t: 64\n"
                "address sizes\t: 32 bits physical, 32 bits virtual\n"
                "power management:\n"
                "\n",
                i, model, num_cpus, i, num_cpus, i, i, flags);
    }
    return 0;
}

#define HAVE_ARCH_PROC_CPUINFO

#endif /* I386_TARGET_PROC_H */
