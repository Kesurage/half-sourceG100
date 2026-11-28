package com.z.halfsourceg100;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Log directory + readers for the v0.4.1 crash-report pipeline.
 *
 * Layout (everything under /sdcard/srceng/logs/ when All-Files-Access is
 * granted; falls back to the app's own external-files dir before that):
 *
 *   crash.txt          native signals + Java exceptions (appended, both traps)
 *   engine-last.txt    marker written by EngineHost right before startActivity:
 *                      game, argv, game index — proves how far launch got
 *   logcat-<n>.txt     "logcat -d" dump captured at launch time (kept: newest 3)
 *   console.log        the engine's own log, WHEN -condebug is on (default
 *                      since v0.4.1) — lands in the game dir, surfaced here
 */
public final class LogStore {

    private static final String TAG = "half-sourceG100";
    private static final int MAX_LOGCAT_FILES = 2;   // v0.6.5: 3 -> 2, each capped 256 KB
    private static final long MAX_TAIL_CHARS = 200_000; // ~200 KB per file shown

    private LogStore() {}

    // --------------------------------------------------------------- cleanup --

    /**
     * v0.6.6 — the log-handler bloat fix. Field complaint (Nov 28): every
     * launch captured a fresh logcat-*.txt (up to 256 KB), every killed run
     * a postmortem-*.txt (up to 256 KB), and NOTHING was ever deleted —
     * after a day of testing the folder held every session since install,
     * and the diagnostics zip bundled it all ("bundles the old log,
     * increasing the size").
     *
     * Called once per app start (after the post-mortem capture, so evidence
     * of the just-killed run survives):
     *   - logcat-*.txt     keep the newest 1, delete the rest
     *   - postmortem-*.txt keep the newest 2, delete the rest
     *   - hsg100-logs-*.zip keep the newest 1, delete the rest
     *   - crash.txt        truncate to the last 64 KB when it grows beyond it
     *   - watchdog.txt     delete when older than 12 h (it self-replaces
     *                      at the first dump of each session anyway)
     */
    public static void cleanupAtLaunch(Context ctx) {
        try {
            File dir = logDir(ctx);
            // logcat dumps: only the newest one has value (the current
            // session is captured fresh at launch anyway)
            pruneNewest(dir, "logcat-", ".txt", 1);
            // post-mortems: the previous run's kill evidence + one spare
            pruneNewest(dir, "postmortem-", ".txt", 2);
            // user-exported zips: keep one spare
            pruneNewest(dir, "hsg100-logs-", ".zip", 1);

            File crash = new File(dir, "crash.txt");
            if (crash.isFile() && crash.length() > 64 * 1024) {
                String t = tail(crash);
                java.io.FileWriter w = new java.io.FileWriter(crash, false);
                w.write(t);
                w.close();
            }

            File wd = new File(dir, "watchdog.txt");
            if (wd.isFile()
                    && System.currentTimeMillis() - wd.lastModified() > 12L * 60 * 60 * 1000) {
                //noinspection ResultOfMethodCallIgnored
                wd.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "log cleanup failed: " + t);
        }
    }

    /** Delete all but the newest {@code keep} files named prefix*suffix. */
    private static void pruneNewest(File dir, String prefix, String suffix, int keep) {
        File[] files = dir.listFiles((d, n) -> n.startsWith(prefix) && n.endsWith(suffix));
        if (files == null || files.length <= keep) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = keep; i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // ------------------------------------------------------------- dirs ----

    /** /sdcard/srceng/logs when shared storage is writable, else app dir. */
    public static File logDir(Context ctx) {
        File shared = new File(ModScanner.ROOT, "logs");
        if (isWritable(shared)) return shared;
        File fallback = ctx.getExternalFilesDir(null);
        if (fallback == null) fallback = ctx.getFilesDir();
        File f = new File(fallback, "logs");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    public static File crashFile(Context ctx) {
        return new File(logDir(ctx), "crash.txt");
    }

    private static boolean isWritable(File dir) {
        try {
            if (!dir.exists()) {
                if (!dir.mkdirs()) return false;
                // probe: can we actually create a file?
                File probe = new File(dir, ".probe");
                boolean ok = probe.createNewFile();
                //noinspection ResultOfMethodCallIgnored
                probe.delete();
                return ok;
            }
            File probe = new File(dir, ".probe");
            boolean ok = probe.createNewFile();
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------- launch mark ----

    /** Written right before the engine activity starts. */
    public static void writeLaunchMarker(Context ctx, String line) {
        try {
            File f = new File(logDir(ctx), "engine-last.txt");
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(line + "\n");
            w.close();
        } catch (IOException e) {
            Log.w(TAG, "launch marker not written: " + e);
        }
    }

    // ------------------------------------------------------ logcat dump ----

    // v0.6.5: the default "logcat -d" dumps the crash buffer too, and the
    // crash buffer keeps the OLDEST tombstone around for days. Every launch
    // re-bundled the same long-fixed Nov 27 FORTIFY abort (844 KB per
    // postmortem, 615 KB per launch logcat) -- pure noise. We now dump only
    // main+system, filtered to OUR pid where possible, and hard-cap the
    // captured file.
    private static final String[] LOGCAT_BUFFERS = {"main", "system"};
    private static final long MAX_CAPTURE_BYTES = 256 * 1024; // 256 KB hard cap

    /** Run logcat into out with buffers+pid filter; hard-cap the file size. */
    private static void runLogcat(File out, Integer pid) {
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("logcat"); cmd.add("-d"); cmd.add("-v"); cmd.add("time");
            for (String b : LOGCAT_BUFFERS) { cmd.add("-b"); cmd.add(b); }
            if (pid != null) { cmd.add("--pid"); cmd.add(String.valueOf(pid)); }
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            java.io.InputStream in = p.getInputStream();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            byte[] buf = new byte[8192];
            long written = 0; int n;
            while ((n = in.read(buf)) > 0) {
                long room = MAX_CAPTURE_BYTES - written;
                if (room <= 0) {
                    // keep the TAIL: restart the file at the last 64 KB
                    fo.close();
                    java.io.RandomAccessFile rf = new java.io.RandomAccessFile(out, "rw");
                    rf.setLength(Math.max(0, rf.length() - 64 * 1024));
                    rf.close();
                    fo = new java.io.FileOutputStream(out, true);
                    written = Math.max(0, MAX_CAPTURE_BYTES - 64 * 1024);
                    room = 64 * 1024;
                }
                int take = (int) Math.min(n, room);
                fo.write(buf, 0, take);
                written += take;
            }
            fo.close();
            p.waitFor();
        } catch (Throwable t) {
            Log.w(TAG, "logcat run failed: " + t);
        }
    }

    /**
     * Best-effort "logcat -d" dump at launch time. No READ_LOGS needed for
     * our own app's lines (log tag "half-sourceG100", "SDL", "ActivityTaskManager"
     * crash hints are usually visible for the app itself).
     */
    public static void captureLogcat(Context ctx) {
        new Thread(() -> {
            try {
                File dir = logDir(ctx);
                File out = new File(dir, "logcat-" + System.currentTimeMillis() + ".txt");
                runLogcat(out, android.os.Process.myPid());
                trimLogcatFiles(dir);
            } catch (Throwable t) {
                Log.w(TAG, "logcat capture failed: " + t);
            }
        }, "logcat-capture").start();
    }

    /**
     * v0.4.2 post-mortem: called at the very start of a new process. If the
     * PREVIOUS run recorded a launch but no exit, it was SIGKILLed (ANR) —
     * its logcat died with it. The kill traces are still in the ring buffer,
     * so dump them now, tagged as post-mortem, before this session's own
     * output buries them.
     *
     * v0.6.5: skip when the last launch is older than POSTMORTEM_MAX_AGE_MS
     * (the old logic kept re-capturing the same ancient kill), and never
     * dump the crash buffer (immortal tombstones, see LOGCAT_BUFFERS).
     */
    private static final long POSTMORTEM_MAX_AGE_MS = 6L * 60 * 60 * 1000; // 6 h

    public static void capturePostMortem(Context ctx) {
        try {
            File dir = logDir(ctx);
            File marker = new File(dir, "engine-last.txt");
            if (!marker.isFile()) return; // previous run never launched
            long age = System.currentTimeMillis() - marker.lastModified();
            if (age > POSTMORTEM_MAX_AGE_MS) return; // stale history, not a fresh kill
            SharedPreferencesEngine.markAppStart(ctx); // this session started
            if (SharedPreferencesEngine.previousRunClean(ctx)) return;
            File out = new File(dir, "postmortem-" + System.currentTimeMillis() + ".txt");
            runLogcat(out, null); // pid unknown for the dead run
            trimPostMortemFiles(dir);
            Log.w(TAG, "previous run appears to have been killed — post-mortem "
                    + "logcat captured: " + out.getName());
        } catch (Throwable t) {
            Log.w(TAG, "post-mortem capture failed: " + t);
        }
    }

    private static void trimPostMortemFiles(File dir) {
        File[] files = dir.listFiles((d, n) ->
                n.startsWith("postmortem-") && n.endsWith(".txt"));
        if (files == null || files.length <= 2) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int excess = files.length - 2;
        for (int i = 0; i < excess; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    /**
     * v0.4.2: move any logs written to the pre-permission fallback dir into
     * the shared dir, so Diagnostics shows one merged history (the v0.4.1
     * field report had run 1 in fallback and run 2 in shared).
     */
    public static void migrateFallbackToShared(Context ctx) {
        try {
            File shared = new File(ModScanner.ROOT, "logs");
            if (!isWritable(shared)) return;
            File fbRoot = ctx.getExternalFilesDir(null);
            if (fbRoot == null) return;
            File fallback = new File(fbRoot, "logs");
            File[] files = fallback.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (!f.isFile()) continue;
                File dst = new File(shared, f.getName());
                if (dst.exists()) {
                    // same name: keep the LARGER copy (more evidence)
                    if (f.length() > dst.length()) {
                        //noinspection ResultOfMethodCallIgnored
                        dst.delete();
                        //noinspection ResultOfMethodCallIgnored
                        f.renameTo(dst);
                    }
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    f.renameTo(dst);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "fallback log migration failed: " + t);
        }
    }

    private static void trimLogcatFiles(File dir) {
        File[] files = dir.listFiles((d, n) -> n.startsWith("logcat-") && n.endsWith(".txt"));
        if (files == null || files.length <= MAX_LOGCAT_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int excess = files.length - MAX_LOGCAT_FILES;
        for (int i = 0; i < excess; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // ---------------------------------------------------------- reading ----

    public static class LogEntry {
        public final File file;
        public final String label;
        LogEntry(File file, String label) { this.file = file; this.label = label; }
    }

    /** All readable logs, newest-first by mtime; label = short description. */
    public static List<LogEntry> listLogs(Context ctx) {
        List<LogEntry> out = new ArrayList<>();
        File dir = logDir(ctx);
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (File f : files) {
                if (!f.isFile() || f.getName().startsWith(".")) continue;
                out.add(new LogEntry(f, labelFor(f.getName())));
            }
        }
        // the engine's own console.log (with -condebug) lives in the game dir
        File consoleLog = new File(ModScanner.ROOT + "/hl2", "console.log");
        if (consoleLog.isFile())
            out.add(new LogEntry(consoleLog, "engine console.log (-condebug)"));
        return out;
    }

    private static String labelFor(String name) {
        switch (name) {
            case "crash.txt": return "crash report (native + Java)";
            case "engine-last.txt": return "last launch parameters";
            case "watchdog.txt": return "hang watchdog dumps (thread states)";
            default:
                if (name.startsWith("logcat-")) return "logcat at launch";
                if (name.startsWith("postmortem-"))
                    return "post-mortem: logcat of a killed run (ANR evidence)";
                return name;
        }
    }

    /**
     * v0.4.2: bundle the CURRENT session's logs (including the engine
     * console.log) into one zip next to the logs, so a single share
     * produces the exact attachment a bug report needs.
     *
     * v0.6.6: session-scoped — files not touched in the last 3 hours are
     * skipped (this is the "stop bundling old logs" fix; the launch-time
     * cleanup bounds the folder as well).
     */
    public static File zipAll(Context ctx) {
        try {
            File dir = logDir(ctx);
            String stamp = new java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                    .format(new java.util.Date());
            File zip = new File(dir, "hsg100-logs-" + stamp + ".zip");
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                    new java.io.FileOutputStream(zip));
            byte[] buf = new byte[8192];
            long freshCutoff = System.currentTimeMillis() - 3L * 60 * 60 * 1000;
            for (LogEntry e : listLogs(ctx)) {
                try {
                    if (e.file.equals(zip) || !e.file.isFile()) continue;
                    boolean marker = e.file.getName().equals("engine-last.txt")
                            || e.file.getName().equals("gameinfo.last.txt");
                    if (!marker && e.file.lastModified() < freshCutoff) continue; // stale
                    zos.putNextEntry(new java.util.zip.ZipEntry(e.file.getName()));
                    try (java.io.FileInputStream in = new java.io.FileInputStream(e.file)) {
                        int n;
                        while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                    }
                    zos.closeEntry();
                } catch (Throwable ignored) {
                    // one unreadable file must not kill the whole zip
                }
            }
            zos.close();
            return zip;
        } catch (Throwable t) {
            Log.w(TAG, "zipAll failed: " + t);
            return null;
        }
    }

    /** Tail of a text file (max ~MAX_TAIL_CHARS), for the diagnostics view. */
    public static String tail(File f) {
        if (f == null || !f.isFile()) return "(not found)";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
            String line;
            long chars = 0;
            while ((line = r.readLine()) != null) {
                lines.addLast(line);
                chars += line.length() + 1;
                if (chars > MAX_TAIL_CHARS * 2) {
                    lines.pollFirst();
                    chars -= 1; // approximate trim; bound is soft
                }
            }
            for (String l : lines) sb.append(l).append('\n');
            if (sb.length() > MAX_TAIL_CHARS)
                sb.delete(0, sb.length() - (int) MAX_TAIL_CHARS);
            if (sb.length() == 0) sb.append("(empty)");
        } catch (Throwable t) {
            sb.append("(unreadable: ").append(t).append(')');
        }
        return sb.toString();
    }
}
