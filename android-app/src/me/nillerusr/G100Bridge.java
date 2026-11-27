package me.nillerusr;

import android.content.Context;

/**
 * half-sourceG100 launcher -> bundled-engine bridge.
 *
 * The bundled engine's ValveActivity2 calls statics off
 * me.nillerusr.LauncherActivity (the original launcher UI this app replaces)
 * for default paths; PKG_NAME must be non-null before the engine activity
 * starts. Lives in this package because the field is package-private in the
 * engine source. (The old-port Scr/obb statics are gone in the v1.17 engine.)
 */
public final class G100Bridge {

    private G100Bridge() {}

    public static void prepare(Context ctx) {
        LauncherActivity.PKG_NAME = ctx.getPackageName();
    }
}
