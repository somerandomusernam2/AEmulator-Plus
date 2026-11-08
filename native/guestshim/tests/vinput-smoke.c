/* Real ARM syscall/socket test under qemu-arm of the emulated touchpad and keyboard ("mouse and keyboard" input method),
 * through the production open() and ioctl() of aemushim.c. Binds /dev/aemu_touchpad and /dev/aemu_keyboard: run as root. */
#include "../aemushim.c"
char **environ;
static int test_errno;
int *__errno(void) { return &test_errno; }
AemuFile *fdopen(int fd, const char *mode) { (void)fd; (void)mode; return 0; }
#define CHECK(c) do { if (!(c)) return __LINE__; } while (0)
static int bit_set(unsigned char *b, unsigned bit) { return b[bit / 8] & (1 << (bit % 8)); }
#define EVIOCGNAME(len) (0x80000000u | ((unsigned)(len) << 16) | ('E' << 8) | 0x06)
#define EVIOCGBIT(ev, len) (0x80000000u | ((unsigned)(len) << 16) | ('E' << 8) | (0x20 + (ev)))
#define EVIOCGABS(a) (0x80000000u | (24u << 16) | ('E' << 8) | (0x40 + (a)))
#define EVIOCGVERSION 0x80044501u
static int streq(const char *a, const char *b) { while (*a && *a == *b) { a++; b++; } return *a == *b; }
static int listen_at(const char *p) {
    sys3(10 /* unlink */, (long)p, 0, 0);
    struct vi_sockaddr a;
    for (unsigned i = 0; i < sizeof a; i++) ((char *)&a)[i] = 0;
    a.family = 1;
    for (int i = 0; p[i]; i++) a.path[i] = p[i];
    int s = (int)vi_socket(1);
    if (s < 0 || sys3(282 /* bind */, s, (long)&a, 2 + sizeof(a.path)) < 0) return -1;
    sys3(284 /* listen */, s, 4, 0);
    return s;
}
static int test_main(void) {
    int pad = listen_at("/dev/aemu_touchpad"), kb = listen_at("/dev/aemu_keyboard");
    CHECK(pad >= 0 && kb >= 0);
    CHECK(vinput_open("/dev/input/event0", 0) == -4096);
    int fp = open(VI_PAD_NODE, 04000 | 02000002), fk = open(VI_KBD_NODE, 02000002);
    CHECK(fp >= 0 && fk >= 0);
    CHECK(vinput_fd(fp) == VI_PAD && vinput_fd(fk) == VI_KBD);
    int d = (int)sys3(63 /* dup2 */, fp, 40, 0);
    CHECK(d == 40 && vinput_fd(d) == VI_PAD);
    char n[64];
    for (int i = 0; i < 64; i++) n[i] = 0;
    CHECK(ioctl(fp, EVIOCGNAME(63), n) > 0 && streq(n, "AEmulator Touchpad"));
    for (int i = 0; i < 64; i++) n[i] = 0;
    CHECK(ioctl(fk, EVIOCGNAME(63), n) > 0 && streq(n, "AEmulator Keyboard"));
    unsigned v = 0;
    CHECK(ioctl(fp, EVIOCGVERSION, &v) == 0 && v == 0x10001);
    unsigned char b[64];
    for (int i = 0; i < 64; i++) b[i] = 0;
    CHECK(ioctl(fp, EVIOCGBIT(0, 8), b) == 8 && bit_set(b, 1) && bit_set(b, 3));       /* EV_KEY, EV_ABS */
    for (int i = 0; i < 64; i++) b[i] = 0;
    CHECK(ioctl(fp, EVIOCGBIT(3, 8), b) == 8 && bit_set(b, 53) && bit_set(b, 54) && bit_set(b, 57));
    int a[6];
    CHECK(ioctl(fp, EVIOCGABS(53), a) == 0 && a[2] == VI_PAD_MAX);
    CHECK(ioctl(fp, EVIOCGABS(10), a) == -1 && test_errno == 22);                       /* an axis it has not */
    for (int i = 0; i < 64; i++) b[i] = 0;
    CHECK(ioctl(fk, EVIOCGBIT(1, 64), b) == 64 && bit_set(b, 30) && bit_set(b, 28));    /* KEY_A, KEY_ENTER */
    int nul = open("/dev/null", 2);
    CHECK(nul >= 0 && vinput_fd(nul) == VI_NONE);
    CHECK(ioctl(nul, EVIOCGVERSION, (void *)&v) == -1 && test_errno == 25);             /* ENOTTY from the kernel */
    unsigned len = sizeof(struct vi_sockaddr);
    struct vi_sockaddr pa;
    int c = (int)sys3(285 /* accept */, pad, (long)&pa, (long)&len);
    CHECK(c >= 0);
    CHECK(sys3(4, c, (long)"abcd", 4) == 4);
    char in[8];
    CHECK(sys3(3, fp, (long)in, 8) == 4 && in[0] == 'a');
    sys3(10, (long)"/dev/aemu_touchpad", 0, 0); sys3(10, (long)"/dev/aemu_keyboard", 0, 0);
    return 0;
}
void _start(void) {
    int r = test_main();
    static const char ok[] = "vinput smoke OK\n", bad[] = "vinput smoke FAILED (line ";
    if (!r) { sys3(4, 1, (long)ok, sizeof ok - 1); sys3(1, 0, 0, 0); }
    char dig[12]; int k = 0, x = r;
    while (x) { dig[k++] = (char)('0' + x % 10); x /= 10; }
    sys3(4, 1, (long)bad, sizeof bad - 1);
    while (k) sys3(4, 1, (long)&dig[--k], 1);
    sys3(4, 1, (long)")\n", 2);
    sys3(1, 1, 0, 0);
}
