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
    private static final int MAX_LOGCAT_FILES = 3;
    private static final long MAX_TAIL_CHARS = 200_000; // ~200 KB per file shown

    private LogStore() {}

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
                Process p = new ProcessBuilder("logcat", "-d", "-v", "time")
                        .redirectErrorStream(true)
                        .start();
                java.io.InputStream in = p.getInputStream();
                java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                fo.close();
                p.waitFor();
                trimLogcatFiles(dir);
            } catch (Throwable t) {
                Log.w(TAG, "logcat capture failed: " + t);
            }
        }, "logcat-capture").start();
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
            default:
                if (name.startsWith("logcat-")) return "logcat at launch";
                return name;
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
