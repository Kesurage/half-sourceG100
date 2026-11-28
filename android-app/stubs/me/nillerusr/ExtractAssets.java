package me.nillerusr;

import android.content.Context;

/**
 * COMPILE-ONLY stub of the engine's asset extractor for the launcher-only
 * flavor. The real class (engine-src) copies assets/extras_dir.vpk (touch UI)
 * into the app's files dir on first run. Not dexed.
 */
public class ExtractAssets {
    public static final String VPK_NAME = "extras_dir.vpk";

    public static final String[] FONTS = {
        "DroidSansFallback.ttf",
        "LiberationMono-Regular.ttf",
        "dejavusans.ttf",
        "dejavusans-bold.ttf",
        "dejavusans-oblique.ttf",
        "dejavusans-boldoblique.ttf",
    };

    public static void extractVPK(Context context, Boolean force) {
    }

    public static boolean extractAsset(Context context, String name, Boolean overwrite) {
        return true;
    }

    public static boolean extractFonts(Context context) {
        return true;
    }
}
