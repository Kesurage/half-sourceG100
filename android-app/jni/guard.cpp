// half-sourceG100 — native crash guard (v0.4.1).
//
// The bundled engine (nillerusr old-port, 2021) dies on modern Android with
// NO trace anywhere the user can reach: no tombstone access, no READ_LOGS,
// and the engine's own console.log only appears with -condebug AND storage
// permission. This guard closes that hole at the lowest level we own.
//
// Java_com_z_halfsourceg100_Hsg100App_installGuard(path) opens the crash
// file and installs handlers for SIGSEGV / SIGABRT / SIGBUS / SIGFPE / SIGILL.
// The handler itself is strictly async-signal-safe: only write() on an fd
// that was opened at install time, no malloc, no backtrace_symbols. It
// records the signal, fault address and PC (arm + aarch64), then restores
// the previous disposition and re-raises so the process still dies normally.
//
// Loaded from Hsg100App.onCreate() BEFORE anything engine-related runs.
#include <jni.h>

#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>

static int g_fd = -1;

// --- tiny async-signal-safe helpers ------------------------------------------
// PUT appends a string literal (length known at compile time).
#define PUT(buf, off, lit) (memcpy((buf) + (off), (lit), sizeof(lit) - 1), \
                            (off) += sizeof(lit) - 1)

// put_hex appends "0x" + hex digits of v (no leading zeros).
static size_t put_hex(char *dst, size_t off, unsigned long v) {
    dst[off++] = '0';
    dst[off++] = 'x';
    const char *d = "0123456789abcdef";
    int started = 0;
    for (int i = (int)(sizeof(v) * 2) - 1; i >= 0; --i) {
        unsigned long nib = (v >> (i * 4)) & 0xF;
        if (!started && nib == 0 && i != 0) continue;
        started = 1;
        dst[off++] = d[nib];
    }
    return off;
}

static void handle_signal(int sig, siginfo_t *info, void *uctx) {
    if (g_fd >= 0) {
        char buf[256];
        size_t off = 0;
        PUT(buf, off, "\n=== hsg100 native crash ===\n");
        PUT(buf, off, "signal ");
        off = put_hex(buf, off, (unsigned long)sig);
        PUT(buf, off, " fault_addr ");
        off = put_hex(buf, off, info ? (unsigned long)info->si_addr : 0UL);
        PUT(buf, off, " pc ");
        unsigned long pc = 0;
#if defined(__aarch64__)
        ucontext_t *uc = (ucontext_t *)uctx;
        pc = (unsigned long)uc->uc_mcontext.pc;
#elif defined(__arm__)
        ucontext_t *uc = (ucontext_t *)uctx;
        pc = (unsigned long)uc->uc_mcontext.arm_pc;
#else
        (void)uctx;
#endif
        off = put_hex(buf, off, pc);
        PUT(buf, off, "\n(pc = faulting instruction; match against "
                      "libmain/libSDL2 .so map)\n");
        (void)write(g_fd, buf, off);
    }

    // restore previous disposition and re-raise: the process must still
    // crash (and reach Android's own reporting) exactly as before
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_DFL;
    sigemptyset(&sa.sa_mask);
    sigaction(sig, &sa, NULL);
    raise(sig);
}

extern "C" JNIEXPORT void JNICALL
Java_com_z_halfsourceg100_Hsg100App_installGuard(
        JNIEnv *env, jclass /*cls*/, jstring path) {
    if (path == nullptr) return;
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (p == nullptr) return;
    if (g_fd >= 0) { close(g_fd); g_fd = -1; }
    g_fd = open(p, O_CREAT | O_APPEND | O_WRONLY, 0666);
    env->ReleaseStringUTFChars(path, p);
    if (g_fd < 0) return;

    static const int sigs[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL};
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = handle_signal;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO;
    for (int s : sigs) sigaction(s, &sa, nullptr);
}
