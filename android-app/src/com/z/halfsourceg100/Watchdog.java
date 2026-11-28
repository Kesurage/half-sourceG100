package com.z.halfsourceg100;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Startup-hang watchdog (v0.4.2) — the answer to the v0.4.1 field report.
 *
 * Field log timeline (Infinix Hot 50 4G, Android 14, v0.4.1):
 *   12:07:11.238  SDL_Android_Init() finished!          (libs load fine)
 *   ... 6 s silence — the engine never draws its first frame ...
 *   12:07:17.293  "Signal Catcher: reacting to signal 3" (SIGQUIT = ANR)
 *   12:07:27      kDebuggerdNativeBacktrace, then SIGKILL
 *
 * crash.txt stayed EMPTY because the engine never crashes — it HANGS, and
 * Android executes it (SIGKILL, uncatchable by design). The only evidence
 * that survives is what we snapshot BEFORE the kill. This watchdog takes
 * that snapshot: full Java thread dumps (every thread's exact line) at
 * T+15 s and T+45 s after launch, plus the logcat tail and the engine's
 * console.log tail (the engine writes it even while hung, if it got that
 * far). Two dumps 30 s apart show whether the engine is progressing or
 * stuck on the same line — that pinpoints the hang site.
 */
public final class Watchdog {

    private static final String TAG = "half-sourceG100";
    private static final long FIRST_DUMP_MS = 15_000;
    private static final long SECOND_DUMP_MS = 45_000;
    private static final int LOGCAT_TAIL_LINES = 800; // v0.6.6: 1500 -> 800 (log-size complaint)

    private static Thread wd;
    /** game dir of the current launch ("hl2" or the TC) — v0.6.4. */
    private static volatile String gameDir = "hl2";

    private Watchdog() {}

    /**
     * Arm (or re-arm) the watchdog right before startActivity(engine).
     * v0.6.4: takes the game dir so the console.log tail is read from the
     * directory the engine ACTUALLY writes it to (a TC launch writes
     * <tc>/console.log — v0.6.3 read hl2/console.log and showed stale
     * output from an older session).
     */
    public static synchronized void arm(Context ctx, String dir) {
        cancel();
        gameDir = dir == null || dir.isEmpty() ? "hl2" : dir;
        final Context app = ctx.getApplicationContext();
        wd = new Thread(() -> run(app), "hsg100-watchdog");
        wd.setDaemon(true);
        wd.start();
        Log.i(TAG, "watchdog armed for " + gameDir + " (dumps at +" + (FIRST_DUMP_MS / 1000)
                + "s and +" + (SECOND_DUMP_MS / 1000) + "s after launch)");
    }

    public static synchronized void cancel() {
        if (wd != null) {
            wd.interrupt();
            wd = null;
        }
    }

    private static void run(Context app) {
        sleep(FIRST_DUMP_MS);
        dump(app, 1);
        sleep(SECOND_DUMP_MS - FIRST_DUMP_MS);
        dump(app, 2);
        // after the second dump the watchdog retires; Android's own ANR
        // machinery takes over from here
        synchronized (Watchdog.class) { wd = null; }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------- dumps --

    private static void dump(Context app, int seq) {
        StringBuilder s = new StringBuilder();
        s.append("\n===== watchdog dump #").append(seq).append(" — T+")
                .append(seq == 1 ? FIRST_DUMP_MS / 1000 : SECOND_DUMP_MS / 1000)
                .append("s after launch =====\n");

        // 1. every Java thread, with its exact suspended line
        s.append(threadDump());

        // 2. logcat tail (native side may have logged its last words).
        //    v0.6.4: BLASTBufferQueue "NO_BUFFER_AVAILABLE" lines are counted
        //    and collapsed — on a black-screen launch they flood at ~300/s and
        //    the old 400-line tail was 100% BLAST spam, hiding every useful
        //    line. The count is itself the key renderer diagnostic: it is the
        //    number of vsyncs the engine left the surface starving.
        //    v0.6.6: "eglSwapBuffersWithDamageKHRImpl: error 300d
        //    (EGL_BAD_SURFACE)" lines are collapsed and counted the same way.
        String lc = logcatTail();
        if (lc != null) s.append("\n----- logcat tail (last ")
                .append(LOGCAT_TAIL_LINES).append(" lines, spam collapsed) -----\n")
                .append(lc);

        // 3. the engine's own console.log, if it exists by now — read from
        //    the ACTIVE game dir first (v0.6.4), hl2 as the fallback
        File consoleLog = new File(new File(ModScanner.ROOT, gameDir), "console.log");
        if (!consoleLog.isFile())
            consoleLog = new File(ModScanner.ROOT + "/hl2", "console.log");
        if (consoleLog.isFile()) {
            s.append("\n----- engine console.log tail (")
                    .append(consoleLog.getParentFile().getName())
                    .append("/console.log) -----\n")
                    .append(LogStore.tail(consoleLog));
        } else {
            s.append("\n----- engine console.log: not created yet (" + gameDir + ") -----");
        }

        File out = new File(LogStore.logDir(app), "watchdog.txt");
        try {
            FileWriter w = new FileWriter(out, seq != 1); // #1 replaces, #2 appends
            w.write(s.toString());
            w.close();
            Log.i(TAG, "watchdog dump #" + seq + " written: " + out);
        } catch (IOException e) {
            Log.w(TAG, "watchdog dump failed: " + e);
        }

        // 4. v0.6.6 renderer-death check — see SDLActivity.requestRendererRecovery().
        //    Every swap of a dead EGL window logs EGL_BAD_SURFACE; a starved
        //    visible surface floods NO_BUFFER_AVAILABLE. Both mean "engine
        //    alive, screen permanently black" — restart before the user has
        //    to fight a frozen app. Thresholds: dump #1 (T+15s) tolerates the
        //    normal pre-first-frame window (splash timeout + menu init can
        //    starve the surface for a few seconds on slow storage); dump #2
        //    (T+45s) expects a long-stable picture.
        if (lastBlastCount >= 0 || lastEglBadCount >= 0) {
            boolean dead = false;
            String why = "";
            if (lastEglBadCount >= 8) {
                dead = true;
                why = lastEglBadCount + " EGL_BAD_SURFACE swap failures";
            } else if (seq == 1 ? lastBlastCount >= 200 : lastBlastCount >= 60) {
                dead = true;
                why = lastBlastCount + " vsyncs with NO buffer presented";
            }
            if (dead) {
                Log.e(TAG, "watchdog: dead-renderer signature (" + why
                        + ") — requesting renderer recovery");
                boolean scheduled = false;
                try {
                    scheduled = org.libsdl.app.SDLActivity.isEngineRunning()
                            && org.libsdl.app.SDLActivity.requestRendererRecovery(why);
                } catch (Throwable t) {
                    Log.w(TAG, "renderer recovery call failed: " + t);
                }
                try {
                    FileWriter w = new FileWriter(out, true);
                    w.write("\n[watchdog] dead-renderer signature: " + why
                            + " — recovery " + (scheduled ? "scheduled (engine restart)"
                                    : "NOT scheduled (engine not running or budget spent)")
                            + "\n");
                    w.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String threadDump() {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        try {
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                Thread t = e.getKey();
                pw.println("\"" + t.getName() + "\" daemon=" + t.isDaemon()
                        + " prio=" + t.getPriority() + " state=" + t.getState());
                for (StackTraceElement el : e.getValue())
                    pw.println("    at " + el);
                pw.println();
            }
        } catch (Throwable t) {
            pw.println("(thread dump failed: " + t + ")");
        }
        return sw.toString();
    }

    /** Counts from the last logcatTail() call — set before the renderer check. */
    private static volatile int lastBlastCount = -1;
    private static volatile int lastEglBadCount = -1;

    private static String logcatTail() {
        try {
            // v0.6.5: buffers main+system only, pid-filtered to this
            // process. The old unfiltered tail included the persistent
            // crash buffer with its immortal (long-fixed) tombstone.
            Process p = new ProcessBuilder("logcat", "-d", "-v", "time",
                            "-t", String.valueOf(LOGCAT_TAIL_LINES),
                            "-b", "main", "-b", "system",
                            "--pid", String.valueOf(android.os.Process.myPid()))
                    .redirectErrorStream(true)
                    .start();
            java.io.InputStream in = p.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            p.waitFor();
            String raw = bo.toString("UTF-8");

            // v0.6.4/6.6: collapse the diagnostic floods (BLAST NO_BUFFER +
            // EGL_BAD_SURFACE) to one summary line each — a black-screen
            // launch produces hundreds of these per second and they would
            // otherwise evict every useful line from the tail.
            StringBuilder sb = new StringBuilder();
            int blast = 0, eglbad = 0;
            for (String line : raw.split("\n")) {
                if (line.contains("BLASTBufferQueue")
                        && line.contains("NO_BUFFER_AVAILABLE")) {
                    blast++;
                    continue;
                }
                if (line.contains("eglSwapBuffers") && line.contains("EGL_BAD_SURFACE")) {
                    eglbad++;
                    continue;
                }
                sb.append(line).append('\n');
            }
            lastBlastCount = blast;
            lastEglBadCount = eglbad;
            if (eglbad > 0) {
                sb.insert(0, "[hsg100: " + eglbad + " eglSwapBuffers EGL_BAD_SURFACE lines"
                        + " collapsed — ANY nonzero means the engine is swapping into a"
                        + " destroyed surface (permanent black screen)]\n");
            }
            if (blast > 0) {
                sb.insert(0, "[hsg100: " + blast + " BLASTBufferQueue NO_BUFFER_AVAILABLE"
                        + " lines collapsed — sustained high counts mean the engine"
                        + " presented NO frames to the visible surface in this window]\n");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(logcat tail failed: " + t + ")";
        }
    }
}
