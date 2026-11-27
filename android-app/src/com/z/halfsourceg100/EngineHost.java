package com.z.halfsourceg100;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;

import me.nillerusr.G100Bridge;

/**
 * Bundled-engine launch path — ONE APK, engine inside (docs/ENGINE-PACK.md).
 *
 * The bundled engine is the nillerusr old-port build (NVIDIA Shield-port
 * lineage, GLES2 via Regal, armeabi-v7a). Its startup contract, recovered
 * from the shipped classes.dex:
 *
 *   SDLActivity.onCreate()
 *     -> ValveActivity2.initNatives()          (engine-side Java callback)
 *        reads SharedPreferences "mod":
 *          gamepath              content root  -> setDocumentDirectoryPath
 *          argv    (default -console)          -> setArgs   (Source cmdline)
 *          env     (default LIBGL_USEVBO=0)    -> setEnvs
 *          game    (int index into the Games table below) -> setGame
 *                                                + LIBRARY_SERVER/LIBRARY_CLIENT
 *          show_touch, resolution_*, fixed_resolution, immersive_mode
 *     -> SDLMain.run() -> nativeInit() -> libengine.so
 *
 * Before SDLActivity can run, its statics (me.nillerusr.LauncherActivity.mPref
 * / scr_res / PKG_NAME / found_main_obb) must be initialized — that is what
 * me.nillerusr.G100Bridge.prepare() does, from this package.
 *
 * Base games launch through the official table index (guaranteed path).
 * TC mods additionally pass "-game <dir>" on the argv; the engine carries a
 * standard Source cmdline parser ("-game %s", gameinfo.txt), and the closest
 * base game index is still set from the TC's lineage so a TC that loses the
 * -game race degrades to its nearest base game instead of failing hard.
 */
public final class EngineHost {

    private static final String TAG = "half-sourceG100";
    private static final String PREFS = "mod"; // engine-side SharedPreferences

    /** Mirrors com.valvesoftware.Games in the bundled engine's dex. */
    public static final String[] BASE_GAMES = {"hl2", "episodic", "ep2", "portal"};

    private EngineHost() {}

    /** True when this APK carries the engine .so set (lib/armeabi-v7a/libmain.so). */
    public static boolean bundled(Activity act) {
        try {
            String dir = act.getApplicationInfo().nativeLibraryDir;
            return dir != null && new File(dir, "libmain.so").isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 32-bit app support is required by the bundled engine (armeabi-v7a). */
    public static boolean deviceAbiOk() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null) return false;
        for (String a : abis) if (a.contains("armeabi")) return true;
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

    /** Index into the bundled engine's game table; unknown dirs fall back to HL2. */
    public static int gameIndex(String dir) {
        if (dir != null)
            for (int i = 0; i < BASE_GAMES.length; i++)
                if (BASE_GAMES[i].equalsIgnoreCase(dir)) return i;
        return 0;
    }

    /**
     * Start the bundled engine.
     *
     * @param tcDir     "" for vanilla Half-Life 2, else the TC folder under /sdcard/srceng
     * @param tcLineage the TC's base-game lineage ("hl2"/"episodic"/"ep2"/"portal")
     *                  used for the fallback game index
     */
    public static void launch(Activity act, String tcDir, String tcLineage) {
        boolean isTc = tcDir != null && !tcDir.isEmpty();
        String gameDir = isTc ? tcDir : "hl2";
        int idx = isTc ? gameIndex(tcLineage) : 0;

        SharedPreferences hsg = act.getSharedPreferences("hsg100", Activity.MODE_PRIVATE);
        String extraArgs = hsg.getString("extra_args", "-console -condebug").trim();
        String env = hsg.getString("engine_env", "LIBGL_USEVBO=0").trim();
        if (extraArgs.isEmpty()) extraArgs = "-console -condebug";

        // argv: user args first, -game override last (single -game on the line wins).
        // -condebug is CRITICAL: it is the only switch that makes the engine
        // write console.log at all — without it a dying engine leaves nothing.
        if (!extraArgs.contains("-condebug")) extraArgs = extraArgs + " -condebug";
        String argv = extraArgs;
        if (isTc) argv = argv + " -game " + tcDir;

        // native panel resolution — the engine renders at the SDL surface size
        DisplayMetrics dm = new DisplayMetrics();
        try {
            act.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        } catch (Throwable t) {
            act.getWindowManager().getDefaultDisplay().getMetrics(dm);
        }
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);

        SharedPreferences p = act.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        p.edit()
                .putString("gamepath", ModScanner.ROOT)
                .putString("argv", argv)
                .putString("env", env)
                .putInt("game", idx)
                .putBoolean("show_touch", true)
                .putInt("resolution_width", w)
                .putInt("resolution_height", h)
                .putBoolean("fixed_resolution", false) // native resolution, always
                .putBoolean("immersive_mode", true)
                .putBoolean("use_volume_buttons", false)
                .apply();

        // engine-side statics must exist before SDLActivity.onCreate
        G100Bridge.prepare(act, w, h);

        Log.i(TAG, "engine launch: game=" + gameDir + " idx=" + idx
                + " argv=" + argv + " res=" + w + "x" + h);

        // crash-report pipeline (v0.4.1): prove how far the launch got
        LogStore.writeLaunchMarker(act, "launch " + new java.util.Date()
                + "\ngame=" + gameDir + " idx=" + idx
                + "\nargv=" + argv
                + "\nenv=" + env
                + "\nroot=" + ModScanner.ROOT
                + "\nprefs=mod (gamepath,argv,env,game,show_touch,res,fixed,immersive)");
        LogStore.captureLogcat(act);

        Intent it = new Intent(act, org.libsdl.app.SDLActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        act.startActivity(it);
    }
}
