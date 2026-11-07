// ashmem shim for x86-32 guests (LD_PRELOAD, bionic firmware)
//
// Android 7.0+ libcutils opens /dev/ashmem, fstat()s it and refuses anything that is not a character device
// ("!S_ISCHR(st.st_mode) || !st.st_rdev" -> close, errno = ENOTTY). Under the translator /dev/ashmem is a memfd, so every
// ashmem_create_region() failed with "Not a typewriter": ART's heap ("ashmem_create_region failed for 'zygote space'"),
// gralloc, MemoryHeapBase. The ARM engine avoids this with libashmemshim.so (preload, replaces the libcutils entry points);
// this is the x86-32 build of the same idea. No dependencies, no libc: raw int 0x80 system calls, so it loads
// into any bionic (or glibc) userland and is not affected by the linker's symbol versioning.
//
// build: native/guestshim/build-x86.sh  ->  app/src/main/assets/engines/kk-x86/libashmemshim.so

typedef unsigned int size_t_;

#define NR_close          6
#define NR_ftruncate     93
#define NR_fstat64      197
#define NR_memfd_create 356
#define MFD_CLOEXEC       1

static inline int sys3(int nr, int a, int b, int c)
{
    int r;
    __asm__ volatile("int $0x80" : "=a"(r) : "0"(nr), "b"(a), "c"(b), "d"(c) : "memory");
    return r;
}

#define EXPORT __attribute__((visibility("default")))

EXPORT int ashmem_create_region(const char *name, size_t_ size)
{
    int fd = sys3(NR_memfd_create, (int)(name && *name ? name : "ashmem"), MFD_CLOEXEC, 0);
    if (fd < 0) {
        return -1;
    }
    if (sys3(NR_ftruncate, fd, (int)size, 0) < 0) {
        sys3(NR_close, fd, 0, 0);
        return -1;
    }
    return fd;
}

// the region's protection is only ever narrowed by callers; a memfd keeps read/write, which is what they need here
EXPORT int ashmem_set_prot_region(int fd, int prot)
{
    (void)fd; (void)prot;
    return 0;
}

EXPORT int ashmem_get_size_region(int fd)
{
    unsigned char st[128];                         // struct stat64 of i386: st_size (64 bit) at offset 44
    if (sys3(NR_fstat64, fd, (int)st, 0) < 0) {
        return -1;
    }
    return (int)(*(unsigned int *)(st + 44));
}

EXPORT int ashmem_pin_region(int fd, size_t_ offset, size_t_ len)
{
    (void)fd; (void)offset; (void)len;
    return 0;                                      // ASHMEM_NOT_PURGED: pages are never taken away
}

EXPORT int ashmem_unpin_region(int fd, size_t_ offset, size_t_ len)
{
    (void)fd; (void)offset; (void)len;
    return 0;
}

// libcutils 7.x: "is this descriptor an ashmem region" (used before pin/unpin/size calls)
EXPORT int ashmem_valid(int fd)
{
    unsigned char st[128];
    return sys3(NR_fstat64, fd, (int)st, 0) == 0;
}
