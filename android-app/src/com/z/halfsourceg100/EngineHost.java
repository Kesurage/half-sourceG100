package com.z.halfsourceg100;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;

import me.nillerusr.G100Bridge;

/**
 * Bundled-engine launch path — ONE APK, engine inside (docs/ENGINE-PACK.md).
 *
 * Since v0.5.0 the bundled engine is the modern nillerusr-lineage build
 * (v1.17.0025, aarch64, togl/GLES3, liblauncher.so entry) — the same engine
 * generation AruMoon's "Source Engine for Android" app ships. It replaces the
 * old 32-bit debug port that hung on startup on Android 14 (see v0.4.2 notes).
 *
 * Startup contract (recovered from srceng-android, verified against the
 * shipped dex of v1.17.0025):
 *
 *   MainActivity (ours) -> EngineHost.launch()
 *       writes SharedPreferences "mod":
 *         gamepath = /sdcard/srceng     (VALVE_GAME_PATH, content root)
 *         argv     = <user args>        (fallback when no intent extra)
 *         env      = KEY=VALUE pairs    (optional)
 *       -> G100Bridge.prepare()         (engine-side statics)
 *       -> ExtractAssets.extractVPK()   (assets/extras_dir.vpk -> filesDir,
 *                                        touch UI; first run only)
 *       -> startActivity(org.libsdl.app.SDLActivity)
 *            extras: gamedir     (hl2 / episodic / ep2 / <tc>)
 *                    gamelibdir  (nativeLibraryDir: bundled client/server)
 *                    vpk         (filesDir/extras_dir.vpk)
 *                    argv        (-console -condebug -novid ...)
 *            -> SDLActivity.init()
 *                 loads SDL2 + launcher (liblauncher.so)
 *                 -> ValveActivity2.preInit()  (gameinfo.txt must exist)
 *                 -> ValveActivity2.initNatives()
 *                      setenv APP_LIB_PATH / APP_DATA_PATH / VALVE_GAME_PATH
 *                      setenv APP_MOD_LIB (gamelibdir) / EXTRAS_VPK_PATH (vpk)
 *                      argv = "-game <gamedir> " + argv
 *                 -> SDLMain -> LauncherMainAndroid (native)
 *
 * TC mods pass gamedir=<tc>; a content-only TC falls back to the bundled HL2
 * client/server code (Tier-2). Regular mods ride hl2/custom/ (engine mounts).
 */
public final class EngineHost {

    private static final String TAG = "half-sourceG100";
    private static final String PREFS = "mod"; // engine-side SharedPreferences

    /** Engine build identifier, shown in diagnostics. */
    public static final String ENGINE_ID = "srceng v1.17.0025 (aarch64/togles)";

    private EngineHost() {}

    /** True when this APK carries the engine .so set (lib/arm64-v8a/liblauncher.so). */
    public static boolean bundled(Activity act) {
        try {
            String dir = act.getApplicationInfo().nativeLibraryDir;
            return dir != null && new File(dir, "liblauncher.so").isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 64-bit (arm64-v8a) device support is required by the bundled engine. */
    public static boolean deviceAbiOk() {
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        if (abis == null) return false;
        for (String a : abis)
            if (a.contains("arm64-v8a")) return true;
        return false;
    }

    public static boolean sdlPresent() {
        try {
            Class.forName("org.libsdl.app.SDLActivity");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * GLES 3.0 minimum for the togl renderer (Mali-G57 MC2: GLES 3.2 — fine).
     */
    public static boolean gles3Ok(Activity act) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    act.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            android.content.pm.ConfigurationInfo ci = am.getDeviceConfigurationInfo();
            return ci != null && ci.reqGlEsVersion >= 0x30000;
        } catch (Throwable t) {
            return true; // cannot tell — let the engine try
        }
    }

    /** Normalize a TC/base-game lineage into a launchable gamedir. */
    public static String gameDirOf(String tcDir, String lineage) {
        boolean isTc = tcDir != null && !tcDir.isEmpty();
        if (!isTc) return "hl2";
        // base games are launched by their own dir name
        if (tcDir.equals("hl2") || tcDir.equals("episodic") || tcDir.equals("ep2") || tcDir.equals("portal"))
            return tcDir;
        return tcDir; // TC: engine gets -game <tcdir>; content-only TCs use
                      // the generated gameinfo.txt + bundled HL2 code
    }

    /**
     * Start the bundled engine.
     *
     * v0.6.0 CONTRACT: gamedir must be argv-safe — the engine's Android entry
     * tokenizes the command line with strtok(argv, " ") and has NO quoting,
     * so a name with a space ("Minerva Metastasis") becomes "-game Minerva"
     * + junk and the engine dies with a fatal missing-gameinfo Error before
     * the first frame. MainActivity runs TcFirstAid (rename + gameinfo regen)
     * before calling this; the guard below is the last line of defense.
     *
     * @param tcDir     "" for vanilla Half-Life 2, else the TC folder under /sdcard/srceng
     * @param tcLineage the TC's base-game lineage (informational; the new engine
     *                  resolves everything through -game <dir> + gameinfo.txt)
     */
    public static void launch(Activity act, String tcDir, String tcLineage) {
        boolean isTc = tcDir != null && !tcDir.isEmpty();
        String gameDir = gameDirOf(tcDir, tcLineage);
        if (TcFirstAid.needsRename(gameDir)) {
            // never reachable through MainActivity (TcFirstAid renames first) —
            // refuse rather than start an engine that is guaranteed to die
            Log.e(TAG, "refusing to launch with argv-unsafe gamedir '" + gameDir + "'");
            return;
        }

        SharedPreferences hsg = act.getSharedPreferences("hsg100", Activity.MODE_PRIVATE);
        String extraArgs = hsg.getString("extra_args", "-console -condebug").trim();
        if (extraArgs.isEmpty()) extraArgs = "-console -condebug";

        // -condebug: engine writes console.log — the single most valuable file
        // when a run dies. -novid: skip the Bink intro (old hang point, and the
        // new engine still pays startup time for it).
        if (!extraArgs.contains("-condebug")) extraArgs = extraArgs + " -condebug";
        if (!extraArgs.contains("-novid")) extraArgs = extraArgs + " -novid";
        String argv = extraArgs;

        // The new engine takes env vars through the "env" pref (KEY=VALUE).
        // Regal-era knobs are gone; keep it empty unless the user sets one.
        String env = hsg.getString("engine_env", "").trim();

        SharedPreferences p = act.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        p.edit()
                .putString("gamepath", ModScanner.ROOT)
                .putString("argv", argv)
                .putString("env", env)
                .putBoolean("immersive_mode", true)
                .apply();

        // engine-side statics (LauncherActivity.PKG_NAME) before SDLActivity
        G100Bridge.prepare(act);

        // touch-UI VPK: extract assets/extras_dir.vpk -> filesDir (first run)
        String vpkPath = "";
        try {
            me.nillerusr.ExtractAssets.extractVPK(act, false);
            vpkPath = act.getFilesDir().getPath() + "/" + me.nillerusr.ExtractAssets.VPK_NAME;
        } catch (Throwable t) {
            Log.w(TAG, "extractVPK failed: " + t);
        }

        // v0.5.1 fix: VGUI fopen()s its fonts from filesDir — without this
        // copy the menu renders invisible (black screen with a live engine)
        try {
            boolean fontsOk = me.nillerusr.ExtractAssets.extractFonts(act);
            Log.i(TAG, "engine fonts extracted: " + fontsOk);
        } catch (Throwable t) {
            Log.w(TAG, "font extraction failed: " + t);
        }

        Log.i(TAG, "engine launch: game=" + gameDir + " argv=" + argv + " vpk=" + vpkPath);

        // crash-report pipeline: prove how far the launch got
        LogStore.writeLaunchMarker(act, "launch " + new java.util.Date()
                + "\nengine=" + ENGINE_ID
                + "\ngame=" + gameDir
                + "\nargv=" + argv
                + "\nenv=" + env
                + "\nroot=" + ModScanner.ROOT
                + "\nvpk=" + vpkPath);

        // run-state marker + hang watchdog: SIGKILL leaves no trace of its own
        SharedPreferencesEngine.markEngineStart(act);
        Watchdog.arm(act, gameDir);
        LogStore.captureLogcat(act);

        Intent it = new Intent(act, org.libsdl.app.SDLActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        it.putExtra("gamedir", gameDir);
        it.putExtra("argv", argv);
        // bundled HL2 client/server game code (Tier-2 fallback for TCs)
        it.putExtra("gamelibdir", act.getApplicationInfo().nativeLibraryDir);
        if (!vpkPath.isEmpty()) it.putExtra("vpk", vpkPath);
        act.startActivity(it);
    }
}
