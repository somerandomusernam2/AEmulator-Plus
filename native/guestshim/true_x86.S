# Empty guest program (x86-32): exit(0) at once. Installed in place of iptables/ip6tables on x86 firmware,
# the same as true.S does for ARM: the emulator has no netfilter, and netd treats an iptables failure as fatal
# for NetworkManagementService (system_server then dies in WifiService with a null INetworkManagementService).
    .text
    .globl _start
_start:
    xorl %ebx, %ebx
    movl $1, %eax
    int $0x80
