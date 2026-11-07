/* Host test of power_service.c's transaction handler against a fake parcel (sh run.sh). The reader below follows the
 * generated android.hardware.power-V6-ndk SupportInfo::readFromParcel (disassembled): read the size, then each field
 * only while (position - start) < size; the nested parcelables are an int32 "not null" and their own size. */
#define AEMU_HOST_TEST 1
#include "../power_service.c"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct AParcel { uint8_t b[512]; int32_t pos, end; };
struct AStatus { int32_t ex; };
static struct AStatus ST_OK = { 0 }, ST_UNSUP = { EX_UNSUPPORTED_OPERATION };
static void put(AParcel *p, const void *v, int n) { memcpy(p->b + p->pos, v, n); p->pos += n; if (p->pos > p->end) p->end = p->pos; }
int32_t AParcel_writeInt32(AParcel *p, int32_t v) { put(p, &v, 4); return 0; }
int32_t AParcel_writeInt64(AParcel *p, int64_t v) { put(p, &v, 8); return 0; }
int32_t AParcel_writeBool(AParcel *p, bool v) { return AParcel_writeInt32(p, v); }
int32_t AParcel_writeString(AParcel *p, const char *s, int32_t len) { AParcel_writeInt32(p, len); put(p, s, len + 1); p->pos = (p->pos + 3) & ~3; if (p->pos > p->end) p->end = p->pos; return 0; }
int32_t AParcel_getDataPosition(const AParcel *p) { return p->pos; }
int32_t AParcel_getDataSize(const AParcel *p) { return p->end; }
int32_t AParcel_setDataPosition(const AParcel *p, int32_t pos) { ((AParcel *)p)->pos = pos; return 0; }
int32_t AParcel_writeStatusHeader(AParcel *p, const AStatus *s) { return AParcel_writeInt32(p, s->ex); }
AStatus *AStatus_newOk(void) { return &ST_OK; }
AStatus *AStatus_fromExceptionCode(int32_t c) { return c == EX_UNSUPPORTED_OPERATION ? &ST_UNSUP : &ST_OK; }
void AStatus_delete(AStatus *s) { (void)s; }

static int fails;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); fails++; } } while (0)

typedef struct { const uint8_t *b; int32_t pos; } R;
static int32_t i32(R *r) { int32_t v; memcpy(&v, r->b + r->pos, 4); r->pos += 4; return v; }
static int64_t i64(R *r) { int64_t v; memcpy(&v, r->b + r->pos, 8); r->pos += 8; return v; }
#define MORE(r, start, size) ((r)->pos - (start) < (size))

int main(void) {
    AParcel out;
    /* getSupportInfo */
    memset(&out, 0, sizeof out);
    CHECK(aemu_power_transact(0, T_GET_SUPPORT_INFO, 0, &out) == 0);
    R r = { out.b, 0 };
    CHECK(i32(&r) == 0);                                   /* status ok */
    CHECK(i32(&r) == 1);                                   /* not null */
    int32_t start = r.pos, size = i32(&r);
    CHECK(size == 96);
    CHECK(out.end == 4 + 4 + 96);                        /* status + not-null flag + the parcelable, nothing after */
    CHECK(MORE(&r, start, size)); CHECK(i32(&r) == 0);     /* usesSessions */
    for (int k = 0; k < 5; k++) { CHECK(MORE(&r, start, size)); CHECK(i64(&r) == 0); }
    CHECK(MORE(&r, start, size)); CHECK(i32(&r) == 1);     /* compositionData not null */
    int32_t cs = r.pos, csize = i32(&r);
    CHECK(csize == 20);
    for (int k = 0; k < 4; k++) { CHECK(MORE(&r, cs, csize)); CHECK(i32(&r) == 0); }
    CHECK(r.pos - cs == csize);
    CHECK(MORE(&r, start, size)); CHECK(i32(&r) == 1);     /* headroom not null */
    int32_t hs = r.pos, hsize = i32(&r);
    CHECK(hsize == 20);
    for (int k = 0; k < 4; k++) { CHECK(MORE(&r, hs, hsize)); CHECK(i32(&r) == 0); }   /* isCpu/GpuSupported, min intervals */
    CHECK(r.pos - hs == hsize);
    CHECK(r.pos - start == size);                          /* the parcelable ends where its size says */
    /* version and hash */
    memset(&out, 0, sizeof out);
    CHECK(aemu_power_transact(0, T_VERSION, 0, &out) == 0);
    r = (R){ out.b, 0 }; CHECK(i32(&r) == 0); CHECK(i32(&r) == INTERFACE_VERSION);
    memset(&out, 0, sizeof out);
    CHECK(aemu_power_transact(0, T_HASH, 0, &out) == 0);
    r = (R){ out.b, 0 }; CHECK(i32(&r) == 0); CHECK(i32(&r) == 9); CHECK(memcmp(out.b + 8, "notfrozen", 9) == 0);
    /* bool replies and unsupported ones */
    for (uint32_t c = T_IS_MODE_SUPPORTED; c <= T_IS_BOOST_SUPPORTED; c += 2) {
        memset(&out, 0, sizeof out);
        CHECK(aemu_power_transact(0, c, 0, &out) == 0);
        r = (R){ out.b, 0 }; CHECK(i32(&r) == 0); CHECK(i32(&r) == 0); CHECK(out.end == 8);
    }
    uint32_t uns[] = { T_CREATE_HINT_SESSION, T_GET_HINT_SESSION_PREFERRED_RATE, T_CREATE_HINT_SESSION_WITH_CONFIG,
                       T_GET_SESSION_CHANNEL, T_CLOSE_SESSION_CHANNEL };
    for (unsigned k = 0; k < sizeof uns / sizeof *uns; k++) {
        memset(&out, 0, sizeof out);
        CHECK(aemu_power_transact(0, uns[k], 0, &out) == 0);
        r = (R){ out.b, 0 }; CHECK(i32(&r) == EX_UNSUPPORTED_OPERATION); CHECK(out.end == 4);
    }
    uint32_t oks[] = { T_SET_MODE, T_SET_BOOST };
    for (unsigned k = 0; k < sizeof oks / sizeof *oks; k++) {
        memset(&out, 0, sizeof out);
        CHECK(aemu_power_transact(0, oks[k], 0, &out) == 0);
        r = (R){ out.b, 0 }; CHECK(i32(&r) == 0); CHECK(out.end == 4);
    }
    /* codes from 10 on are told apart by their arguments, whatever their number (pre-release images number them differently) */
    AParcel noargs = { {0}, 24, 24 }, args = { {0}, 24, 40 };   /* position at the end / 16 bytes of arguments left */
    for (uint32_t c = 10; c <= 40; c++) {
        memset(&out, 0, sizeof out);
        CHECK(aemu_power_transact(0, c, &noargs, &out) == 0);
        r = (R){ out.b, 0 }; CHECK(i32(&r) == 0); CHECK(i32(&r) == 1); CHECK(i32(&r) == 96); CHECK(out.end == 4 + 4 + 96);
        memset(&out, 0, sizeof out);
        CHECK(aemu_power_transact(0, c, &args, &out) == 0);
        r = (R){ out.b, 0 }; CHECK(i32(&r) == EX_UNSUPPORTED_OPERATION); CHECK(out.end == 4);
    }
    memset(&out, 0, sizeof out);
    CHECK(aemu_power_transact(0, T_GET_SUPPORT_INFO, 0, &out) == 0 && out.end == 4 + 4 + 96);   /* no parcel: no arguments */
    CHECK(aemu_power_transact(0, 0, 0, &out) == UNKNOWN_TRANSACTION);
    CHECK(aemu_power_transact(0, 0xfffffd, &noargs, &out) == 0);   /* still in the shape range */
    CHECK(aemu_power_transact(0, 0x1000000, &noargs, &out) == UNKNOWN_TRANSACTION);
    printf(fails ? "%d FAILED\n" : "all passed\n", fails);
    return fails != 0;
}
