package com.z.halfsourceg100;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * v0.4.2 run-state tracker — tells a clean exit from a kill on the next boot.
 *
 * The engine process can die four ways:
 *   1. user backs out / finishes         -> clean, nothing to capture
 *   2. Java exception                    -> crash.txt (v0.4.1 trap)
 *   3. native signal                     -> crash.txt (v0.4.1 guard)
 *   4. ANR / LMK SIGKILL                 -> NOTHING (uncatchable) — the only
 *      trace is the logcat ring, which dies with the process.
 *
 * This class is how case 4 is detected after the fact: the engine launch
 * marks RUN_ENGINE, an app start with that marker still set = previous run
 * never shut down cleanly = capture post-mortem logcat NOW (the kill traces
 * are still in the ring buffer at that point).
 */
public final class SharedPreferencesEngine {

    private static final String PREFS = "runstate";
    private static final String K_RUN = "run";          // "", "launcher", "engine"
    private static final String K_LAST_START = "last_start";

    private SharedPreferencesEngine() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Called at every process start (before anything else logs). */
    public static void markAppStart(Context ctx) {
        prefs(ctx).edit()
                .putLong(K_LAST_START, System.currentTimeMillis())
                .commit();
    }

    /** True when the previous run shut down cleanly (no engine left running). */
    static boolean previousRunClean(Context ctx) {
        // The marker is cleared whenever the engine session ends cleanly.
        // Still set to "engine" at app start => the process was killed
        // while the engine was up (ANR) — post-mortem needed.
        return !"engine".equals(prefs(ctx).getString(K_RUN, ""));
    }

    /** Engine activity is going up; a clean teardown must call markEngineEnd. */
    public static void markEngineStart(Context ctx) {
        prefs(ctx).edit().putString(K_RUN, "engine").commit();
    }

    /** Clean engine shutdown (onDestroy without a kill). */
    public static void markEngineEnd(Context ctx) {
        prefs(ctx).edit().putString(K_RUN, "").commit();
    }
}
