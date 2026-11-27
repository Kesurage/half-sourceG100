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
    private static final int LOGCAT_TAIL_LINES = 400;

    private static Thread wd;

    private Watchdog() {}

    /** Arm (or re-arm) the watchdog right before startActivity(engine). */
    public static synchronized void arm(Context ctx) {
        cancel();
        final Context app = ctx.getApplicationContext();
        wd = new Thread(() -> run(app), "hsg100-watchdog");
        wd.setDaemon(true);
        wd.start();
        Log.i(TAG, "watchdog armed (dumps at +" + (FIRST_DUMP_MS / 1000)
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

        // 2. logcat tail (native side may have logged its last words)
        String lc = logcatTail();
        if (lc != null) s.append("\n----- logcat tail (last ")
                .append(LOGCAT_TAIL_LINES).append(" lines) -----\n").append(lc);

        // 3. the engine's own console.log, if it exists by now
        File consoleLog = new File(ModScanner.ROOT + "/hl2", "console.log");
        if (consoleLog.isFile()) {
            s.append("\n----- engine console.log tail -----\n")
                    .append(LogStore.tail(consoleLog));
        } else {
            s.append("\n----- engine console.log: not created yet -----\n");
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

    private static String logcatTail() {
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-t", String.valueOf(LOGCAT_TAIL_LINES))
                    .redirectErrorStream(true)
                    .start();
            java.io.InputStream in = p.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            p.waitFor();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return "(logcat tail failed: " + t + ")";
        }
    }
}
