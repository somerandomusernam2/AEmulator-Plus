/* Emulator-owned evdev touchpad and keyboard for the "mouse and keyboard" input method.
 * Same technique as trackball.h: the guest's EventHub finds an empty node in /dev/input (made by the app), open()
 * returns a socket to the app and the EVIOC* questions are answered here, so no guest binary or file is patched.
 * Self-contained: needs only sys3() and fail() from the including shim. Used by the ARM shim (aemushim.c) and the x86-32 shim
 * (aemushim_x86.c); the two differ in how a socket is made (own system calls on ARM, socketcall on i386). */
#ifndef VINPUT_H
#define VINPUT_H

#define VI_PAD_NODE "/dev/input/aemu-touchpad"
#define VI_PAD_SOCKET "/dev/aemu_touchpad"
#define VI_KBD_NODE "/dev/input/aemu-keyboard"
#define VI_KBD_SOCKET "/dev/aemu_keyboard"
#define VI_NONE 0
#define VI_PAD 1
#define VI_KBD 2
#define VI_PAD_MAX 4095

struct vi_sockaddr { unsigned short family; char path[108]; };

static int vi_equal(const char *a, const char *b) {
    if (!a) return 0;
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
}

#if defined(__i386__)
/* i386 reaches sockets through socketcall(2): the call number and a pointer to the argument words. */
static long vi_socketcall(long call, unsigned long *args) { return sys3(102 /* socketcall */, call, (long)args, 0); }
static long vi_socket(long type) { unsigned long a[3] = { 1 /* AF_UNIX */, (unsigned long)type, 0 }; return vi_socketcall(1, a); }
static long vi_connect(long fd, const void *addr, unsigned long len) {
    unsigned long a[3] = { (unsigned long)fd, (unsigned long)addr, len };
    return vi_socketcall(3, a);
}
static long vi_getpeername(long fd, void *addr, unsigned *len) {
    unsigned long a[3] = { (unsigned long)fd, (unsigned long)addr, (unsigned long)len };
    return vi_socketcall(7, a);
}
#else
static long vi_socket(long type) { return sys3(281 /* socket */, 1 /* AF_UNIX */, type, 0); }
static long vi_connect(long fd, const void *addr, unsigned long len) { return sys3(283 /* connect */, fd, (long)addr, (long)len); }
static long vi_getpeername(long fd, void *addr, unsigned *len) { return sys3(287 /* getpeername */, fd, (long)addr, (long)len); }
#endif

static const char *vi_socket_of(const char *path) {
    if (vi_equal(path, VI_PAD_NODE)) return VI_PAD_SOCKET;
    if (vi_equal(path, VI_KBD_NODE)) return VI_KBD_SOCKET;
    return 0;
}

/* Raw result; -4096 means the path is not one of ours. */
static long vinput_open(const char *path, int flags) {
    const char *sock = vi_socket_of(path);
    if (!sock) return -4096;
    if (flags & 0100 /* O_CREAT */) return -22;
    struct vi_sockaddr addr;
    for (unsigned i = 0; i < sizeof(addr); i++) ((unsigned char *)&addr)[i] = 0;
    addr.family = 1;
    for (unsigned i = 0; sock[i]; i++) addr.path[i] = sock[i];
    long fd = vi_socket(1 | (flags & 02000000));
    if (fd < 0) return fd;
    // the whole sun_path capacity is passed: QEMU rewrites the guest-root pathname in place
    long r = vi_connect(fd, &addr, sizeof(addr));
    if (r >= 0 && (flags & 04000)) r = sys3(55 /* fcntl */, fd, 4 /* F_SETFL */, 04000);
    if (r < 0) { sys3(6, fd, 0, 0); return r; }
    return fd;
}

/* Which of our devices a descriptor is (by its peer, so dup/fork keep working), VI_NONE otherwise. */
static int vinput_fd(int fd) {
    struct vi_sockaddr addr;
    for (unsigned i = 0; i < sizeof(addr); i++) ((unsigned char *)&addr)[i] = 0;
    unsigned len = sizeof(addr);
    if (vi_getpeername(fd, &addr, &len) < 0 || addr.family != 1) return VI_NONE;
    unsigned n = 0;
    while (n < sizeof(addr.path) && addr.path[n]) n++;
    for (int kind = VI_PAD; kind <= VI_KBD; kind++) {
        const char *suffix = kind == VI_PAD ? VI_PAD_SOCKET : VI_KBD_SOCKET;
        unsigned sl = 0;
        while (suffix[sl]) sl++;
        if (n < sl) continue;
        unsigned i = 0;
        while (i < sl && addr.path[n - sl + i] == suffix[i]) i++;
        if (i == sl) return kind;
    }
    return VI_NONE;
}

static void vi_bit(unsigned char *buf, unsigned len, unsigned bit) {
    if (bit / 8 < len) buf[bit / 8] |= 1u << (bit % 8);
}

static int vinput_ioctl(int kind, unsigned req, void *arg) {
    unsigned nr = req & 255, len = (req >> 16) & 0x3fff;
    unsigned char *b = arg;
    if (nr == 0x90 /* EVIOCGRAB */ || nr == 0xa0 /* EVIOCSCLOCKID */ || nr == 0x03 /* EVIOCSREP */) return 0;
    if (!b) return fail(-14 /* EFAULT */);
    if (!(req & 0x80000000u)) return fail(-25 /* ENOTTY */);
    if (nr == 1 /* EVIOCGVERSION */ && len == 4) { *(unsigned *)b = 0x10001; return 0; }
    if (nr == 2 /* EVIOCGID */ && len == 8) {
        unsigned short *id = arg;
        id[0] = 6 /* BUS_VIRTUAL */; id[1] = 0; id[2] = (unsigned short)(kind == VI_PAD ? 2 : 3); id[3] = 1;
        return 0;
    }
    if (nr >= 6 && nr <= 8) { /* name, physical path, unique ID */
        const char *s = kind == VI_PAD ? (nr == 6 ? "AEmulator Touchpad" : nr == 7 ? "aemu/touchpad" : "aemu-touchpad")
                                       : (nr == 6 ? "AEmulator Keyboard" : nr == 7 ? "aemu/keyboard" : "aemu-keyboard");
        unsigned i = 0;
        if (!len) return 0;
        while (i + 1 < len && s[i]) { b[i] = s[i]; i++; }
        b[i++] = 0;
        return (int)i;
    }
    if (nr == 9 /* properties */ || nr == 0x18 /* key state */ || nr == 0x19 || nr == 0x1a || nr == 0x1b ||
        (nr >= 0x20 && nr <= 0x3f)) {
        for (unsigned i = 0; i < len; i++) b[i] = 0;
        if (nr == 9 && kind == VI_PAD) vi_bit(b, len, 0 /* INPUT_PROP_POINTER */);
        if (nr == 0x20) { /* EV bits */
            vi_bit(b, len, 0 /* EV_SYN */); vi_bit(b, len, 1 /* EV_KEY */);
            if (kind == VI_PAD) vi_bit(b, len, 3 /* EV_ABS */);
        }
        if (nr == 0x21) { /* KEY bits */
            if (kind == VI_KBD) { for (unsigned k = 1; k < 249; k++) vi_bit(b, len, k); }
            else { vi_bit(b, len, 272 /* BTN_LEFT */); vi_bit(b, len, 273 /* BTN_RIGHT */); vi_bit(b, len, 330 /* BTN_TOUCH */); }
        }
        if (nr == 0x23 && kind == VI_PAD) { /* ABS bits: protocol A multi-touch */
            vi_bit(b, len, 48); vi_bit(b, len, 53); vi_bit(b, len, 54); vi_bit(b, len, 57); vi_bit(b, len, 58);
        }
        return (int)len;
    }
    if (nr >= 0x40 && nr < 0x80 && kind == VI_PAD) { /* EVIOCGABS(axis): value, min, max, fuzz, flat[, resolution] */
        int *a = arg;
        unsigned axis = nr - 0x40, words = len / 4;
        for (unsigned i = 0; i < words && i < 6; i++) a[i] = 0;
        if (words < 3) return fail(-22 /* EINVAL */);
        if (axis == 53 || axis == 54) a[2] = VI_PAD_MAX;
        else if (axis == 48 || axis == 58) a[2] = 255;
        else if (axis == 57) a[2] = 31;
        else return fail(-22 /* EINVAL */);
        return 0;
    }
    return fail(-25 /* ENOTTY */);
}
#endif
