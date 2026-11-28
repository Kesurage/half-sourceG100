package me.nillerusr;

import android.content.SharedPreferences;
import android.os.Environment;
import java.io.File;

/**
 * COMPILE-ONLY stub of the engine's launcher class for the launcher-only
 * flavor (the real body comes from android-app/engine-src and is compiled
 * into the engine-flavor APK). Field and method signatures mirror
 * engine-src/me/nillerusr/LauncherActivity.java exactly.
 * This file is compiled on the javac classpath but MUST NOT be dexed.
 */
public class LauncherActivity {
    public static String PKG_NAME;

    public static String getDefaultDir() {
        File dir = Environment.getExternalStorageDirectory();
        if (dir == null || !dir.exists())
            return "/sdcard/";
        return dir.getPath();
    }

    public static String getAndroidDataDir() {
        String path = getDefaultDir() + "/Android/data/" + PKG_NAME + "/files";
        File directory = new File(path);
        if (!directory.exists())
            directory.mkdirs();
        return path;
    }
}
