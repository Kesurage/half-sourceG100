package org.libsdl.app;

/**
 * COMPILE-ONLY stub of the SDL activity from the bundled engine source
 * (engine-src/org/libsdl/app/SDLActivity.java, compiled into the engine
 * flavor APK). Referenced so our launcher can address it at compile time;
 * never dexed into the launcher-only flavor.
 *
 * v0.6.6: mirrors the new renderer-recovery API (isEngineRunning /
 * requestRendererRecovery) the Watchdog calls.
 */
public class SDLActivity extends android.app.Activity {

    /** True once the native engine thread has been started. */
    public static boolean isEngineRunning() {
        return false; // stub: no engine in this flavor
    }

    /** Last-resort renderer recovery (engine restart). Stub: no-op. */
    public static boolean requestRendererRecovery(String reason) {
        return false;
    }
}
