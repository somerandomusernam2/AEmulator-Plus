/*
 * eglprobe: what does the guest's EGL/SwiftShader accept?
 *
 * SurfaceFlinger (Android 9) calls eglCreateContext once and aborts with "EGLContext creation failed" when it
 * gets EGL_NO_CONTEXT. Nothing logs the EGL error. This program goes through the same libEGL the guest's
 * programs use (the Android meta-EGL, which then loads the driver the properties select), lists every config
 * with its renderable-type bits, and tries to create a context on each one for client version 2 and 3 and with
 * no version attribute, printing eglGetError() after every call. A context that is created is made current on a
 * 16x16 pbuffer (when the config allows one) and glGetString() is read.
 *
 * Everything is dlopen'ed, so it needs nothing but libc and libdl at build time. Output goes to logcat (tag
 * EGLPROBE), to stderr and to /data/local/tmp/eglprobe.txt (EGLPROBE_OUT overrides the path).
 * After it has run it sleeps for good, so a service manager that restarts exits does not loop it.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef void *EGLDisplay, *EGLConfig, *EGLContext, *EGLSurface;
typedef int32_t EGLint;
typedef unsigned EGLBoolean;

#define EGL_NONE 0x3038
#define EGL_SUCCESS 0x3000
#define EGL_VENDOR 0x3053
#define EGL_VERSION 0x3054
#define EGL_EXTENSIONS 0x3055
#define EGL_CLIENT_APIS 0x308D
#define EGL_OPENGL_ES_API 0x30A0
#define EGL_CONFIG_ID 0x3028
#define EGL_RENDERABLE_TYPE 0x3040
#define EGL_SURFACE_TYPE 0x3033
#define EGL_CONFIG_CAVEAT 0x3027
#define EGL_RED_SIZE 0x3024
#define EGL_GREEN_SIZE 0x3023
#define EGL_BLUE_SIZE 0x3022
#define EGL_ALPHA_SIZE 0x3021
#define EGL_DEPTH_SIZE 0x3025
#define EGL_STENCIL_SIZE 0x3026
#define EGL_NATIVE_VISUAL_ID 0x302E
#define EGL_WIDTH 0x3057
#define EGL_HEIGHT 0x3056
#define EGL_CONTEXT_CLIENT_VERSION 0x3098
#define EGL_PBUFFER_BIT 0x0001
#define EGL_WINDOW_BIT 0x0004
#define EGL_OPENGL_ES_BIT 0x0001
#define EGL_OPENGL_ES2_BIT 0x0004
#define EGL_OPENGL_ES3_BIT 0x0040
#define GL_VENDOR 0x1F00
#define GL_RENDERER 0x1F01
#define GL_VERSION 0x1F02

static FILE *g_out;
static int (*g_logwrite)(int, const char *, const char *);

static void say(const char *fmt, ...)
{
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    if (g_logwrite) g_logwrite(4 /* ANDROID_LOG_INFO */, "EGLPROBE", buf);
    fprintf(stderr, "%s\n", buf);
    if (g_out) { fprintf(g_out, "%s\n", buf); fflush(g_out); }
}

static const char *egl_err(EGLint e)
{
    switch (e) {
    case 0x3000: return "EGL_SUCCESS";
    case 0x3001: return "EGL_NOT_INITIALIZED";
    case 0x3002: return "EGL_BAD_ACCESS";
    case 0x3003: return "EGL_BAD_ALLOC";
    case 0x3004: return "EGL_BAD_ATTRIBUTE";
    case 0x3005: return "EGL_BAD_CONFIG";
    case 0x3006: return "EGL_BAD_CONTEXT";
    case 0x3007: return "EGL_BAD_CURRENT_SURFACE";
    case 0x3008: return "EGL_BAD_DISPLAY";
    case 0x3009: return "EGL_BAD_MATCH";
    case 0x300A: return "EGL_BAD_NATIVE_PIXMAP";
    case 0x300B: return "EGL_BAD_NATIVE_WINDOW";
    case 0x300C: return "EGL_BAD_PARAMETER";
    case 0x300D: return "EGL_BAD_SURFACE";
    case 0x300E: return "EGL_CONTEXT_LOST";
    default: return "?";
    }
}

static const char *type_bits(EGLint t, char *b, size_t n)
{
    snprintf(b, n, "%s%s%s", (t & EGL_OPENGL_ES_BIT) ? "ES1 " : "", (t & EGL_OPENGL_ES2_BIT) ? "ES2 " : "",
             (t & EGL_OPENGL_ES3_BIT) ? "ES3 " : "");
    return b;
}

static void prop(const char *name)
{
    int (*get)(const char *, char *) = (int (*)(const char *, char *))dlsym(RTLD_DEFAULT, "__system_property_get");
    char v[96] = "";
    if (get) get(name, v);
    say("prop %s = '%s'%s", name, v, get ? "" : " (no __system_property_get)");
}

#define SYM(h, var, name) do { *(void **)&(var) = dlsym((h), (name)); if (!(var)) { say("missing symbol %s", (name)); return 2; } } while (0)

int main(void)
{
    const char *path = getenv("EGLPROBE_OUT");
    g_out = fopen(path ? path : "/data/local/tmp/eglprobe.txt", "w");
    void *log = dlopen("liblog.so", RTLD_NOW);
    if (log) *(void **)&g_logwrite = dlsym(log, "__android_log_write");

    say("eglprobe: start, pid %d, %zu-bit", (int)getpid(), sizeof(void *) * 8);
    prop("qemu.gles"); prop("ro.kernel.qemu.gles"); prop("ro.hardware.egl"); prop("ro.kernel.qemu");
    prop("ro.product.cpu.abi"); prop("ro.build.version.sdk");

    void *egl = dlopen("libEGL.so", RTLD_NOW);
    if (!egl) { say("dlopen libEGL.so failed: %s", dlerror()); goto done; }

    EGLDisplay (*eglGetDisplay)(void *);
    EGLBoolean (*eglInitialize)(EGLDisplay, EGLint *, EGLint *);
    const char *(*eglQueryString)(EGLDisplay, EGLint);
    EGLBoolean (*eglBindAPI)(unsigned);
    EGLBoolean (*eglGetConfigs)(EGLDisplay, EGLConfig *, EGLint, EGLint *);
    EGLBoolean (*eglGetConfigAttrib)(EGLDisplay, EGLConfig, EGLint, EGLint *);
    EGLContext (*eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
    EGLBoolean (*eglDestroyContext)(EGLDisplay, EGLContext);
    EGLSurface (*eglCreatePbufferSurface)(EGLDisplay, EGLConfig, const EGLint *);
    EGLBoolean (*eglDestroySurface)(EGLDisplay, EGLSurface);
    EGLBoolean (*eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
    EGLint (*eglGetError)(void);
    SYM(egl, eglGetDisplay, "eglGetDisplay");
    SYM(egl, eglInitialize, "eglInitialize");
    SYM(egl, eglQueryString, "eglQueryString");
    SYM(egl, eglBindAPI, "eglBindAPI");
    SYM(egl, eglGetConfigs, "eglGetConfigs");
    SYM(egl, eglGetConfigAttrib, "eglGetConfigAttrib");
    SYM(egl, eglCreateContext, "eglCreateContext");
    SYM(egl, eglDestroyContext, "eglDestroyContext");
    SYM(egl, eglCreatePbufferSurface, "eglCreatePbufferSurface");
    SYM(egl, eglDestroySurface, "eglDestroySurface");
    SYM(egl, eglMakeCurrent, "eglMakeCurrent");
    SYM(egl, eglGetError, "eglGetError");

    EGLDisplay dpy = eglGetDisplay(NULL);
    say("eglGetDisplay(EGL_DEFAULT_DISPLAY) = %p, error %s", dpy, egl_err(eglGetError()));
    EGLint maj = 0, min = 0;
    EGLBoolean ok = eglInitialize(dpy, &maj, &min);
    say("eglInitialize = %u (EGL %d.%d), error %s", ok, maj, min, egl_err(eglGetError()));
    if (!ok) goto done;
    say("EGL_VENDOR      : %s", eglQueryString(dpy, EGL_VENDOR));
    say("EGL_VERSION     : %s", eglQueryString(dpy, EGL_VERSION));
    say("EGL_CLIENT_APIS : %s", eglQueryString(dpy, EGL_CLIENT_APIS));
    say("EGL_EXTENSIONS  : %s", eglQueryString(dpy, EGL_EXTENSIONS));
    say("eglBindAPI(OPENGL_ES) = %u, error %s", eglBindAPI(EGL_OPENGL_ES_API), egl_err(eglGetError()));

    void *gl2 = dlopen("libGLESv2.so", RTLD_NOW);
    const unsigned char *(*glGetString)(unsigned) = gl2 ? (const unsigned char *(*)(unsigned))dlsym(gl2, "glGetString") : NULL;
    if (!gl2) say("dlopen libGLESv2.so failed: %s", dlerror());

    EGLint total = 0;
    eglGetConfigs(dpy, NULL, 0, &total);
    say("configs: %d (error %s)", total, egl_err(eglGetError()));
    if (total <= 0) goto done;
    if (total > 128) total = 128;
    EGLConfig cfg[128];
    EGLint n = 0;
    eglGetConfigs(dpy, cfg, total, &n);

    for (int i = 0; i < n; i++) {
        EGLint id = 0, rt = 0, st = 0, r = 0, g = 0, b = 0, a = 0, d = 0, s = 0, vis = 0, cav = 0;
        eglGetConfigAttrib(dpy, cfg[i], EGL_CONFIG_ID, &id);
        eglGetConfigAttrib(dpy, cfg[i], EGL_RENDERABLE_TYPE, &rt);
        eglGetConfigAttrib(dpy, cfg[i], EGL_SURFACE_TYPE, &st);
        eglGetConfigAttrib(dpy, cfg[i], EGL_RED_SIZE, &r);
        eglGetConfigAttrib(dpy, cfg[i], EGL_GREEN_SIZE, &g);
        eglGetConfigAttrib(dpy, cfg[i], EGL_BLUE_SIZE, &b);
        eglGetConfigAttrib(dpy, cfg[i], EGL_ALPHA_SIZE, &a);
        eglGetConfigAttrib(dpy, cfg[i], EGL_DEPTH_SIZE, &d);
        eglGetConfigAttrib(dpy, cfg[i], EGL_STENCIL_SIZE, &s);
        eglGetConfigAttrib(dpy, cfg[i], EGL_NATIVE_VISUAL_ID, &vis);
        eglGetConfigAttrib(dpy, cfg[i], EGL_CONFIG_CAVEAT, &cav);
        char tb[32];
        say("config[%d] handle=%p id=%d rgba=%d%d%d%d depth=%d stencil=%d visual=0x%x caveat=0x%x surface=0x%x%s%s renderable=0x%x (%s)",
            i, cfg[i], id, r, g, b, a, d, s, vis, cav, st, (st & EGL_WINDOW_BIT) ? " WINDOW" : "",
            (st & EGL_PBUFFER_BIT) ? " PBUFFER" : "", rt, type_bits(rt, tb, sizeof tb));

        for (int variant = 0; variant < 3; variant++) {
            static const int ver[3] = { 2, 3, 0 };
            EGLint attr[3] = { EGL_CONTEXT_CLIENT_VERSION, ver[variant], EGL_NONE };
            eglGetError();   /* clear */
            EGLContext ctx = eglCreateContext(dpy, cfg[i], NULL, ver[variant] ? attr : NULL);
            EGLint e = eglGetError();
            say("  config[%d] eglCreateContext(%s) = %p, error %s", i,
                variant == 0 ? "version 2" : variant == 1 ? "version 3" : "no attributes", ctx, egl_err(e));
            if (!ctx) continue;
            if (st & EGL_PBUFFER_BIT) {
                EGLint pa[5] = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };
                EGLSurface sf = eglCreatePbufferSurface(dpy, cfg[i], pa);
                EGLint e2 = eglGetError();
                if (sf) {
                    EGLBoolean cur = eglMakeCurrent(dpy, sf, sf, ctx);
                    EGLint e3 = eglGetError();
                    say("    pbuffer %p, eglMakeCurrent = %u, error %s", sf, cur, egl_err(e3));
                    if (cur && glGetString) {
                        const unsigned char *v = glGetString(GL_VERSION), *rr = glGetString(GL_RENDERER), *vv = glGetString(GL_VENDOR);
                        say("    GL_VERSION  : %s", v ? (const char *)v : "(null)");
                        say("    GL_RENDERER : %s", rr ? (const char *)rr : "(null)");
                        say("    GL_VENDOR   : %s", vv ? (const char *)vv : "(null)");
                    }
                    eglMakeCurrent(dpy, NULL, NULL, NULL);
                    eglDestroySurface(dpy, sf);
                } else say("    eglCreatePbufferSurface failed, error %s", egl_err(e2));
            }
            eglDestroyContext(dpy, ctx);
        }
    }

done:
    say("eglprobe: done");
    if (g_out) fclose(g_out);
    for (;;) sleep(3600);
}
