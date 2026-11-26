package com.z.halfsourceg100;

/**
 * Bridge to the native engine bundle.
 *
 * The engine ships its own SDL/togles backend; the JNI surface here is the
 * minimal Phase 0 contract (lifecycle + paths). As the fork evolves these
 * become the engine's own entry points (engine API, not SDL's).
 *
 * Launcher-only builds ship without the engine .so set — loading is guarded
 * so the mod catalog still works and the launch button explains the state.
 */
public final class EngineJni {
    private static boolean available = false;

    static {
        try {
            // load order matters: tier0 and vstdlib first, engine last
            System.loadLibrary("vstdlib");
            System.loadLibrary("tier0");
            System.loadLibrary("SDL2");
            System.loadLibrary("engine");
            available = true;
        } catch (Throwable t) {
            available = false; // engine bundle not shipped in this build
        }
    }

    private EngineJni() {}

    public static boolean available() { return available; }

    public static native void onPause();
    public static native void onResume();
    public static native void onShutdown();
}
