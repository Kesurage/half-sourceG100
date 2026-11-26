package com.z.halfsourceg100;

/**
 * Bridge to the native engine.
 *
 * The engine ships its own SDL/togles backend; the JNI surface here is the
 * minimal Phase 0 contract (lifecycle + paths). As the fork evolves these
 * become the engine's own entry points (engine API, not SDL's).
 */
public final class EngineJni {
    static {
        // load order matters: tier0 and vstdlib first, engine last
        System.loadLibrary("vstdlib");
        System.loadLibrary("tier0");
        System.loadLibrary("SDL2");
        System.loadLibrary("engine");
    }

    public static native void onPause();
    public static native void onResume();
    public static native void onShutdown();
}
