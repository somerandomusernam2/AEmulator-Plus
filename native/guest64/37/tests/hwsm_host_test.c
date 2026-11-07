/* Host test of hwservicemanager.c: a fake binder driver (the layout the engine's front end gives: data | offsets | buffers,
 * pointers inside a parent re-set) drives the manager through add / get / getTransport / registerForNotifications and
 * checks its replies.   cc -DAEMU_HOST_TEST -o t hwsm_host_test.c && ./t */
#define _GNU_SOURCE
#include <assert.h>
#include <stdint.h>
#define main hwsm_main
#include "../hwservicemanager.c"
#undef main

static char g_root[256];
const char *tb_root(void) { return g_root; }
int tb_setprop(const char *n, const char *v) { fprintf(stderr, "setprop %s=%s\n", n, v); return 0; }
int tb_open(const char *dev) { (void)dev; return 3; }
void *tb_mmap(int fd, size_t len) { (void)fd; return malloc(len); }

/* ---- scripted peer ---- */
struct step { int kind; u8 *blob; size_t len; };               /* a BR_* stream to hand out on the next read */
static struct step g_q[32]; static int g_qh, g_qt;
static u8 g_rep[64][8192]; static size_t g_replen[64]; static u32 g_repcmd[64]; static int g_nrep;   /* BC_REPLY(_SG) / BC_TRANSACTION(_SG) the manager sent */
static struct btd g_rep_tr[64]; static u8 g_rep_data[64][4096]; static u64 g_rep_offs[64][32]; static u8 g_rep_sg[64][4096];

static int g_death_reqs, g_acq, g_free;
static int g_chain_answered;

static int g_front;
static void q_push(const void *b, size_t n) {
    int at = g_qt;
    if (g_front) { memmove(&g_q[g_qh + 1], &g_q[g_qh], (size_t)(g_qt - g_qh) * sizeof g_q[0]); at = g_qh; }
    g_q[at].blob = malloc(n); memcpy(g_q[at].blob, b, n); g_q[at].len = n; g_qt++;
}

/* a "driver delivered" transaction: data (A8) | offsets | sg buffers; the buffer pointers patched like bread() does */
struct tp { u8 d[2048]; size_t dl; u64 o[16]; size_t no; u8 sg[4096]; size_t sgl; };
static void tp_i32(struct tp *t, i32 v) { memcpy(t->d + t->dl, &v, 4); t->dl += 4; }
static void tp_cstr(struct tp *t, const char *s) { size_t n = strlen(s) + 1; memcpy(t->d + t->dl, s, n); t->dl += (n + 3) & ~3u; }
static int tp_buf(struct tp *t, const void *b, u64 len, int parent, u64 poff) {   /* returns object index */
    struct bbo o; memset(&o, 0, sizeof o); o.type = T_PTR; o.length = len;
    o.buffer = t->sgl; /* offset for now, made absolute by tp_deliver */
    memcpy(t->sg + t->sgl, b, len); t->sgl += A8(len);
    if (parent >= 0) { o.flags = 1; o.parent = (u64)parent; o.parent_offset = poff; }
    t->o[t->no] = t->dl; memcpy(t->d + t->dl, &o, sizeof o); t->dl += sizeof o;
    return (int)t->no++;
}
static void tp_string(struct tp *t, const char *s) {
    struct hstr h; memset(&h, 0, sizeof h); h.size = (u32)strlen(s);
    int p = tp_buf(t, &h, sizeof h, -1, 0);
    tp_buf(t, s, strlen(s) + 1, p, 0);
}
/* a vec<string>: the hidl_vec struct, its array of hidl_string (a child of the struct) and the characters (children of the array) */
static void tp_strvec(struct tp *t, const char *const *ss, u32 n) {
    u8 vec[16]; memset(vec, 0, sizeof vec); memcpy(vec + 8, &n, 4); vec[12] = 1;
    int v = tp_buf(t, vec, sizeof vec, -1, 0);
    struct hstr hs[8]; memset(hs, 0, sizeof hs);
    for (u32 i = 0; i < n; i++) hs[i].size = (u32)strlen(ss[i]);
    int a = tp_buf(t, hs, n * sizeof(struct hstr), v, 0);
    for (u32 i = 0; i < n; i++) tp_buf(t, ss[i], strlen(ss[i]) + 1, a, i * sizeof(struct hstr));
}
static void tp_handle(struct tp *t, u32 handle) {
    struct fbo f; memset(&f, 0, sizeof f); f.type = T_HANDLE; f.flags = 0x17f; f.binder = handle;
    t->o[t->no++] = t->dl; memcpy(t->d + t->dl, &f, sizeof f); t->dl += sizeof f;
}
static void tp_deliver(struct tp *t, u32 br, u32 code, u32 flags) {
    size_t dsz = (t->dl + 7) & ~7u, total = dsz + t->no * 8 + t->sgl;
    u8 *buf = malloc(total + 8); memset(buf, 0, total + 8);
    memcpy(buf, t->d, t->dl); memcpy(buf + dsz, t->o, t->no * 8); memcpy(buf + dsz + t->no * 8, t->sg, t->sgl);
    u64 *offs = (u64 *)(buf + dsz); u8 *sg = buf + dsz + t->no * 8;
    for (size_t i = 0; i < t->no; i++) {
        u32 ty; memcpy(&ty, buf + offs[i], 4);
        if (ty != T_PTR) continue;
        struct bbo *b = (struct bbo *)(buf + offs[i]);
        u64 rel = b->buffer; b->buffer = (u64)(uptr)(sg + rel);
        if (b->flags & 1) { struct bbo *par = (struct bbo *)(buf + offs[b->parent]); *(u64 *)(uptr)(par->buffer + b->parent_offset) = b->buffer; }
    }
    struct btd tr; memset(&tr, 0, sizeof tr);
    tr.code = code; tr.flags = flags; tr.data_size = t->dl; tr.offsets_size = t->no * 8; tr.buffer = (u64)(uptr)buf; tr.offsets = (u64)(uptr)offs; tr.sender_pid = 77;
    u8 out[512]; size_t k = 0; u32 noop = 0x0000720c; memcpy(out, &noop, 4); k = 4;
    memcpy(out + k, &br, 4); k += 4; memcpy(out + k, &tr, sizeof tr); k += sizeof tr;
    q_push(out, k);
}

static int g_next_is_chain_reply;
int tb_ioctl(int fd, unsigned long cmd, void *arg) {
    (void)fd;
    if (cmd == BINDER_VERSION) { ((i32 *)arg)[0] = 8; return 0; }
    if (cmd != BINDER_WRITE_READ) return 0;
    struct bwr *b = arg; const u8 *p = (const u8 *)(uptr)b->write_buffer, *e = p + b->write_size;
    while (p < e) {
        u32 c; memcpy(&c, p, 4); p += 4; u32 sz = (c >> 16) & 0x3fff;
        if (c == BC_REPLY || c == BC_REPLY_SG || c == BC_TRANSACTION || c == BC_TRANSACTION_SG) {
            struct btd tr; memcpy(&tr, p, sizeof tr);
            int i = g_nrep++; g_repcmd[i] = c; g_rep_tr[i] = tr;
            memcpy(g_rep_data[i], (void *)(uptr)tr.buffer, tr.data_size);
            memcpy(g_rep_offs[i], (void *)(uptr)tr.offsets, tr.offsets_size);
            /* the driver copies the buffers: lay them out after the data, patch parent pointers, as bread() would */
            size_t at = 0; u64 *offs = g_rep_offs[i];
            for (size_t k = 0; k < tr.offsets_size / 8; k++) {
                struct bbo *o = (struct bbo *)(g_rep_data[i] + offs[k]);
                if (o->type != T_PTR) continue;
                memcpy(g_rep_sg[i] + at, (void *)(uptr)o->buffer, o->length);
                o->buffer = (u64)(uptr)(g_rep_sg[i] + at); at += A8(o->length);
                if (o->flags & 1) { struct bbo *par = (struct bbo *)(g_rep_data[i] + offs[o->parent]); *(u64 *)(uptr)(par->buffer + o->parent_offset) = o->buffer; }
            }
            g_rep_tr[i].buffer = (u64)(uptr)g_rep_data[i]; g_rep_tr[i].offsets = (u64)(uptr)g_rep_offs[i];
            if ((c == BC_TRANSACTION || c == BC_TRANSACTION_SG) && tr.code == IB_CHAIN && !(tr.flags & TF_ONE_WAY)) g_next_is_chain_reply = 1;
            if (c == BC_TRANSACTION || c == BC_TRANSACTION_SG) { /* a BR_TRANSACTION_COMPLETE */ }
        } else if (c == BC_REQUEST_DEATH_NOTIFICATION) g_death_reqs++;
        else if (c == BC_ACQUIRE) g_acq++;
        else if (c == BC_FREE_BUFFER) g_free++;
        p += sz;
    }
    b->write_consumed = b->write_size;
    if (!b->read_size) return 0;
    if (g_next_is_chain_reply) {                 /* the service answers interfaceChain: [composer@2.1::IComposer, IBase] */
        g_next_is_chain_reply = 0; g_chain_answered = 1;
        struct tp t; memset(&t, 0, sizeof t); tp_i32(&t, 0);
        struct hstr vec; memset(&vec, 0, sizeof vec); vec.size = 2;
        const char *names[2] = { "android.hardware.graphics.composer@2.1::IComposer", "android.hidl.base@1.0::IBase" };
        struct hstr arr[2]; memset(arr, 0, sizeof arr); arr[0].size = (u32)strlen(names[0]); arr[1].size = (u32)strlen(names[1]);
        int pv = tp_buf(&t, &vec, sizeof vec, -1, 0);
        int pa = tp_buf(&t, arr, sizeof arr, pv, 0);
        tp_buf(&t, names[0], strlen(names[0]) + 1, pa, 0);
        tp_buf(&t, names[1], strlen(names[1]) + 1, pa, sizeof(struct hstr));
        g_front = 1; tp_deliver(&t, BR_REPLY, 0, 0); g_front = 0;
    }
    if (g_qh == g_qt) { extern void check(void); check(); exit(0); }
    struct step *s = &g_q[g_qh++];
    memcpy((void *)(uptr)b->read_buffer, s->blob, s->len); b->read_consumed = s->len;
    return 0;
}

/* a reply from the manager: the first object parse helpers */
static int rep_i32(int i, size_t at) { i32 v; memcpy(&v, g_rep_data[i] + at, 4); return v; }
static const char *rep_str(int i, int obj_struct) { struct bbo *c = (struct bbo *)(g_rep_data[i] + g_rep_offs[i][obj_struct + 1]); return (const char *)(uptr)c->buffer; }

static void write_manifest(void) {
    char path[400];
    snprintf(path, sizeof path, "mkdir -p %s/vendor/etc/vintf", g_root); system(path);
    snprintf(path, sizeof path, "%s/vendor/etc/vintf/manifest.xml", g_root);
    FILE *f = fopen(path, "w");
    fputs("<manifest version=\"1.0\" type=\"device\">\n"
          " <!-- a comment <hal> -->\n"
          " <hal format=\"hidl\"><name>android.hardware.graphics.composer</name><transport>hwbinder</transport><version>2.1</version>\n"
          "   <interface><name>IComposer</name><instance>default</instance></interface></hal>\n"
          " <hal format=\"hidl\"><name>android.hardware.graphics.mapper</name><transport arch=\"32+64\">passthrough</transport><version>2.0</version>\n"
          "   <interface><name>IMapper</name><instance>default</instance></interface></hal>\n"
          " <hal format=\"aidl\"><name>android.hardware.light</name><fqname>ILights/default</fqname></hal>\n"
          "</manifest>\n", f);
    fclose(f);
}

int main(void) {
    snprintf(g_root, sizeof g_root, "/tmp/hwsm-test-root-%d", (int)getpid());
    write_manifest();
    const char *FQ = "android.hardware.graphics.composer@2.1::IComposer";
    struct tp t;
    /* 1. getTransport(composer@2.1, default) -> hwbinder; mapper@2.0 -> passthrough; unknown -> empty */
    memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_string(&t, FQ); tp_string(&t, "default"); tp_deliver(&t, BR_TRANSACTION, SM_GET_TRANSPORT, 0);
    memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_string(&t, "android.hardware.graphics.mapper@2.0::IMapper"); tp_string(&t, "default"); tp_deliver(&t, BR_TRANSACTION, SM_GET_TRANSPORT, 0);
    memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_string(&t, "android.hardware.foo@1.0::IFoo"); tp_string(&t, "default"); tp_deliver(&t, BR_TRANSACTION, SM_GET_TRANSPORT, 0);
    /* 2. get before the service exists -> null binder */
    memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_string(&t, FQ); tp_string(&t, "default"); tp_deliver(&t, BR_TRANSACTION, SM_GET, 0);
    /* 3. registerForNotifications(composer, default, callback handle 9) */
    memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_string(&t, FQ); tp_string(&t, "default"); tp_handle(&t, 9); tp_deliver(&t, BR_TRANSACTION, SM_REGISTER_FOR_NOTIFICATIONS, 0);
    /* 4. add("default", service handle 5): the manager asks the service for its interface chain */
    memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_string(&t, "default"); tp_handle(&t, 5); tp_deliver(&t, BR_TRANSACTION, SM_ADD, 0);
    /* 5. get again -> handle 5; listByInterface; a malformed call (a string object missing) must be refused, not crash */
    memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_string(&t, FQ); tp_string(&t, "default"); tp_deliver(&t, BR_TRANSACTION, SM_GET, 0);
    memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_string(&t, FQ); tp_deliver(&t, BR_TRANSACTION, SM_LIST_BY_INTERFACE, 0);
    memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_deliver(&t, BR_TRANSACTION, SM_GET, 0);
    memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_string(&t, FQ); tp_string(&t, "default"); tp_deliver(&t, BR_TRANSACTION, SM_GET, 0);
    memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_deliver(&t, BR_TRANSACTION, IB_CHAIN, 0);
    /* 6. the manager registers itself (system_server's WifiService asks for android.hidl.manager@1.2::IServiceManager/default) */
    memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_string(&t, "android.hidl.manager@1.2::IServiceManager"); tp_string(&t, "default"); tp_deliver(&t, BR_TRANSACTION, SM_GET, 0);
    memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_string(&t, "android.hidl.manager@1.2::IServiceManager"); tp_string(&t, "default"); tp_deliver(&t, BR_TRANSACTION, SM_GET_TRANSPORT, 0);
    /* 7. addWithChain(\"default\", service handle 6, [audio@7.0::IDevicesFactory, IBase]) of a client with libhidl of 11+: no question
     * to the service; then get(audio@7.0, default) finds handle 6. A call of the same numbers with another shape is refused. */
    { const char *const chain[2] = { "android.hardware.audio@7.0::IDevicesFactory", "android.hidl.base@1.0::IBase" };
      memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_string(&t, "default"); tp_handle(&t, 6); tp_strvec(&t, chain, 2); tp_deliver(&t, BR_TRANSACTION, SM_ADD_WITH_CHAIN, 0);
      memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_string(&t, "android.hardware.audio@7.0::IDevicesFactory"); tp_string(&t, "default"); tp_deliver(&t, BR_TRANSACTION, SM_GET, 0);
      memset(&t, 0, sizeof t); tp_cstr(&t, MANAGER_DESC); tp_string(&t, "default"); tp_deliver(&t, BR_TRANSACTION, SM_ADD_WITH_CHAIN, 0); }

    return hwsm_main(0, NULL, NULL);
}

#define OK(c) do { if (!(c)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #c); exit(1); } } while (0)
void check(void) {
    fprintf(stderr, "replies sent: %d\n", g_nrep);
    OK(g_nrep == 18);
    for (int i = 0; i < 3; i++) { OK(g_repcmd[i] == BC_REPLY); OK(rep_i32(i, 0) == 0); }
    OK(rep_i32(0, 4) == TR_HWBINDER); OK(rep_i32(1, 4) == TR_PASSTHROUGH); OK(rep_i32(2, 4) == TR_EMPTY);
    OK(g_rep_tr[3].offsets_size == 0 && g_rep_tr[3].data_size == 4 + 24); { u32 ty; memcpy(&ty, g_rep_data[3] + 4, 4); OK(ty == T_BINDER); }
    OK(rep_i32(4, 0) == 0 && rep_i32(4, 4) == 1);
    OK(g_repcmd[5] == BC_TRANSACTION && g_rep_tr[5].code == IB_CHAIN && g_rep_tr[5].target == 5);
    OK(g_repcmd[6] == BC_TRANSACTION_SG && g_rep_tr[6].code == NOTIFY_ON_REGISTRATION && g_rep_tr[6].target == 9 && (g_rep_tr[6].flags & TF_ONE_WAY));
    OK(strcmp((const char *)g_rep_data[6], "android.hidl.manager@1.0::IServiceNotification") == 0);
    OK(g_repcmd[7] == BC_REPLY && rep_i32(7, 0) == 0 && rep_i32(7, 4) == 1);
    { u32 ty; memcpy(&ty, g_rep_data[8] + 4, 4); OK(ty == T_HANDLE); struct fbo f; memcpy(&f, g_rep_data[8] + 4, sizeof f); OK(f.binder == 5); OK(g_rep_tr[8].offsets_size == 8); }
    OK(g_repcmd[9] == BC_REPLY_SG && rep_i32(9, 0) == 0); OK(strcmp(rep_str(9, 1), "default") == 0);
    OK(g_rep_tr[10].flags & TF_STATUS_CODE);
    { struct fbo f; memcpy(&f, g_rep_data[11] + 4, sizeof f); OK(f.type == T_HANDLE && f.binder == 5); }
    OK(g_repcmd[12] == BC_REPLY_SG); OK(strcmp(rep_str(12, 1), MANAGER_DESC) == 0);
    /* get(manager@1.2, default): a local binder object (binder 0 = this context manager's node) in the list of objects, not a null */
    { struct fbo f; memcpy(&f, g_rep_data[13] + 4, sizeof f); OK(f.type == T_BINDER && f.binder == 0 && f.cookie == 0); OK(g_rep_tr[13].offsets_size == 8); }
    OK(rep_i32(14, 0) == 0 && rep_i32(14, 4) == TR_HWBINDER);
    OK(g_repcmd[15] == BC_REPLY && rep_i32(15, 0) == 0 && rep_i32(15, 4) == 1);
    { struct fbo f; memcpy(&f, g_rep_data[16] + 4, sizeof f); OK(f.type == T_HANDLE && f.binder == 6); }
    OK(g_rep_tr[17].flags & TF_STATUS_CODE);
    OK(g_chain_answered); OK(g_death_reqs == 3 && g_acq == 3);
    fprintf(stderr, "ALL OK (death requests %d, acquires %d, buffers freed %d)\n", g_death_reqs, g_acq, g_free);
}
