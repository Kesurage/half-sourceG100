package me.nillerusr;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * half-sourceG100 launcher -> bundled-engine bridge.
 *
 * The bundled engine's SDLActivity.onCreate() calls
 * com.valvesoftware.ValveActivity2.initNatives(), which reads statics off
 * me.nillerusr.LauncherActivity (the original launcher UI that this app
 * replaces). Those statics must be initialized BEFORE the engine activity
 * starts — that is this class's only job. Lives in this package because the
 * fields are package-private in the shipped engine dex.
 */
public final class G100Bridge {

    private G100Bridge() {}

    public static void prepare(Context ctx, int width, int height) {
        LauncherActivity.PKG_NAME = ctx.getPackageName();
        LauncherActivity.mPref = ctx.getSharedPreferences("mod", Context.MODE_PRIVATE);

        Screen.Resolution r = new Screen.Resolution();
        r.width = width;
        r.height = height;
        LauncherActivity.scr_res = r;

        // no NVIDIA OBB packs on this port — loose files under /sdcard/srceng
        LauncherActivity.found_main_obb = null;
    }
}
