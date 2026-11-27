package com.z.halfsourceg100;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Process-wide crash trap — installed before ANYTHING else (v0.4.1).
 *
 * The v0.4.0 reports were "it crashes and there is no log anywhere". Root
 * cause of the silence: the bundled engine dies inside nativeInit() where
 * neither Android (no READ_LOGS, no tombstone access) nor the engine
 * (-condebug never set) leaves a user-reachable trace.
 *
 * This class owns three layers:
 *
 *   1. Native signal guard (libtier2jni.so, jni/guard.cpp): SIGSEGV/SIGABRT/
 *      SIGBUS/SIGFPE/SIGILL write signal/pc to the crash file with open+write
 *      only — async-signal-safe, no malloc.
 *   2. Java uncaught-exception handler: full stack trace to the same file.
 *   3. logcat capture: on every engine launch, "logcat -d" is dumped to the
 *      logs dir by LogStore so even pre-guard messages survive.
 *
 * All files live under /sdcard/srceng/logs/ (best effort — before storage
 * permission is granted they land in the app's external files dir instead,
 * which Diagnostics reads first).
 */
public class Hsg100App extends Application {

    public static final String TAG = "half-sourceG100";

    @Override
    public void onCreate() {
        super.onCreate();

        File dir = LogStore.logDir(this);
        boolean inShared = dir != null
                && dir.getAbsolutePath().startsWith("/sdcard");
        File crash = LogStore.crashFile(this);

        // 1. native signal guard — only if libtier2jni.so is present and the
        //    crash file is reachable (shared storage needs All-Files-Access)
        if (crash != null) {
            try {
                System.loadLibrary("tier2jni");
                installGuard(crash.getAbsolutePath());
                Log.i(TAG, "native crash guard armed: " + crash);
            } catch (Throwable t) {
                Log.w(TAG, "native guard unavailable: " + t);
            }
        }

        // 2. Java crash trap — always on, works even without any native lib
        installJavaTrap();

        Log.i(TAG, "hsg100 app up; logs dir = " + (dir != null ? dir : "null")
                + (inShared ? " (shared storage)" : " (app-private fallback)"));
    }

    /**
     * Chained handler: keeps a reference to the platform handler so our trap
     * can delegate after writing the crash file.
     */
    private static volatile Thread.UncaughtExceptionHandler previous;

    /** Install the Java trap, remembering the platform handler to chain to. */
    private void installJavaTrap() {
        previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeJavaCrash(LogStore.crashFile(this), thread, throwable);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    private static void writeJavaCrash(File file, Thread thread, Throwable t) {
        if (file == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            SimpleDateFormat df =
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            String record = "\n=== hsg100 Java crash === "
                    + df.format(new Date()) + "\n"
                    + "thread: " + thread.getName() + "\n"
                    + sw + "\n";
            FileWriter w = new FileWriter(file, true);
            w.write(record);
            w.close();
        } catch (Throwable ignored) {
            // a crash trap must never throw
        }
    }

    private static native void installGuard(String path);
}
