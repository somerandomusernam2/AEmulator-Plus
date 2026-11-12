/* Host test of native/guestshim/binderguard_core.h: gcc -O1 -o /tmp/bgt binderguard_test.c && /tmp/bgt
 * Transaction data and offsets live in MAP_32BIT memory because the 32-bit binder structs hold 32-bit pointers. */
#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include "../native/guestshim/binderguard_core.h"

static int events[8], freed;
static void *guard_alloc(u32 len) {
    void *p = mmap(0, (len + 4095u) & ~4095u, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_32BIT, -1, 0);
    return p == MAP_FAILED ? 0 : p;
}
static void guard_free(void *p, u32 len) { munmap(p, (len + 4095u) & ~4095u); freed++; }
static void guard_event(int kind, u32 cmd, u32 code, u32 pid, u32 type, u32 b, u32 c) {
    (void)cmd; (void)code; (void)pid; (void)type; (void)b; (void)c; events[kind]++;
}
static int fails;
#define CHECK(x) do { if (!(x)) { printf("FAIL line %d: %s\n", __LINE__, #x); fails++; } } while (0)

struct msg { unsigned char *data; u32 *offs; };
static struct msg make(u32 type, u32 flags, u32 binder, u32 cookie) {
    struct msg m;
    m.data = guard_alloc(64); m.offs = guard_alloc(8);
    memset(m.data, 0, 64);
    u32 *o = (u32 *)(m.data + 8);
    o[0] = type; o[1] = flags; o[2] = binder; o[3] = cookie;
    m.offs[0] = 8;
    return m;
}
/* a command stream holding one transaction-data command */
static void cmd_td(unsigned char *out, u32 cmd, struct msg m, u32 code, u32 tflags) {
    u32 *c = (u32 *)out;
    memset(out, 0, 44);
    c[0] = cmd;
    u32 *t = c + 1;
    t[2] = code; t[3] = tflags; t[4] = 777; t[6] = 40; t[7] = 4;
    t[8] = (u32)(unsigned long)m.data; t[9] = (u32)(unsigned long)m.offs;
}
static void run_read(unsigned char *buf, long n, int enforce) {
    struct g_bwr b = {0};
    b.read_buffer = (unsigned long)buf; b.read_consumed = n; b.read_size = 256;
    guard_after(&b, 0, enforce);
}
static void run_write(unsigned char *buf, long n) {
    struct g_bwr b = {0};
    b.write_buffer = (unsigned long)buf; b.write_size = n;
    guard_before(&b);
}

int main(void) {
    unsigned char w[64], r[64];

    /* 1: an object this process sent out comes back as BINDER: left alone */
    struct msg own = make(TYPE_BINDER_G, 0x7f, 0x1000, 0x2000);
    cmd_td(w, BC_TRANSACTION_G, own, 1, 0);
    run_write(w, 44);
    cmd_td(r, BR_REPLY_G, own, 0, 0);
    run_read(r, 44, 1);
    CHECK(((u32 *)r)[9] == (u32)(unsigned long)own.data);
    CHECK(events[1] == 0);

    /* 2: a foreign BINDER object is cleared in a private copy, the original stays */
    struct msg bad = make(TYPE_BINDER_G, 0x7f, 0x5000, 0x6000);
    cmd_td(r, BR_REPLY_G, bad, 4, 0);
    run_read(r, 44, 1);
    u32 priv = ((u32 *)r)[9];
    CHECK(priv != (u32)(unsigned long)bad.data);
    CHECK(((u32 *)(unsigned long)priv)[2 + 2] == 0 && ((u32 *)(unsigned long)priv)[2 + 3] == 0);
    CHECK(((u32 *)(bad.data + 8))[2] == 0x5000);
    CHECK(((u32 *)(unsigned long)priv)[2] == TYPE_BINDER_G);
    CHECK(events[1] == 1);
    u32 *po = (u32 *)(unsigned long)((u32 *)r)[10];
    CHECK(po[0] == 8);

    /* 3: freeing the copy gives the daemon the original buffer back */
    u32 fb[2] = { BC_FREE_BUFFER_G, priv };
    run_write((unsigned char *)fb, 8);
    CHECK(fb[1] == (u32)(unsigned long)bad.data);
    CHECK(freed == 1);
    u32 fb2[2] = { BC_FREE_BUFFER_G, (u32)(unsigned long)own.data };   /* an ordinary buffer is not touched */
    run_write((unsigned char *)fb2, 8);
    CHECK(fb2[1] == (u32)(unsigned long)own.data);

    /* 4: BR_INCREFS teaches the table: the same object is then local */
    struct msg inc = make(TYPE_BINDER_G, 0x7f, 0x7000, 0x8000);
    u32 ic[3] = { BR_INCREFS_G, 0x7000, 0x8000 };
    run_read((unsigned char *)ic, 12, 1);
    cmd_td(r, BR_TRANSACTION_G, inc, 3, 0);
    run_read(r, 44, 1);
    CHECK(((u32 *)r)[9] == (u32)(unsigned long)inc.data);

    /* 5: handles, null objects, status replies and weak objects */
    struct msg h = make(0x73682a85 /* BINDER_TYPE_HANDLE */, 0x7f, 3, 0);
    cmd_td(r, BR_REPLY_G, h, 0, 0); run_read(r, 44, 1);
    CHECK(((u32 *)r)[9] == (u32)(unsigned long)h.data);
    struct msg nul = make(TYPE_BINDER_G, 0, 0, 0);
    cmd_td(r, BR_REPLY_G, nul, 0, 0); run_read(r, 44, 1);
    CHECK(((u32 *)r)[9] == (u32)(unsigned long)nul.data);
    struct msg st = make(TYPE_BINDER_G, 0, 0x9000, 0xa000);
    cmd_td(r, BR_REPLY_G, st, 0, 8 /* TF_STATUS_CODE */); run_read(r, 44, 1);
    CHECK(((u32 *)r)[9] == (u32)(unsigned long)st.data);
    int before = events[1];
    struct msg wk = make(TYPE_WEAK_G, 0, 0xb000, 0xc000);
    cmd_td(r, BR_TRANSACTION_G, wk, 0, 0); run_read(r, 44, 1);
    CHECK(events[1] == before + 1);

    /* 6: probe mode only reports */
    struct msg pb = make(TYPE_BINDER_G, 0, 0xd000, 0xe000);
    cmd_td(r, BR_REPLY_G, pb, 0, 0); run_read(r, 44, 0);
    CHECK(((u32 *)r)[9] == (u32)(unsigned long)pb.data);
    CHECK(events[2] == 1);

    /* 7: bad geometry is ignored */
    struct msg g = make(TYPE_BINDER_G, 0, 0xf000, 0xf100);
    g.offs[0] = 60;   /* object would end past data_size */
    cmd_td(r, BR_REPLY_G, g, 0, 0); run_read(r, 44, 1);
    CHECK(((u32 *)r)[9] == (u32)(unsigned long)g.data);

    printf(fails ? "FAILED (%d)\n" : "binderguard core: all checks passed\n", fails);
    return fails != 0;
}
