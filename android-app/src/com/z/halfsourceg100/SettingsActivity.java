package com.z.halfsourceg100;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Launcher setup: content root info, engine launch tuning (argv / env), bulk
 * actions (restore original gameinfos, disable all regular mods), and the
 * project identity text.
 */
public class SettingsActivity extends Activity {

    private EditText extraArgs, engineEnv;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences("hsg100", MODE_PRIVATE);

        String activeTc = getSharedPreferences("launcher", MODE_PRIVATE)
                .getString("active_tc", "");

        ((TextView) findViewById(R.id.root)).setText(ModScanner.ROOT
                + "\n(same root the bundled engine mounts — shared storage)");
        ((TextView) findViewById(R.id.active_tc)).setText(
                activeTc == null || activeTc.isEmpty()
                        ? "none — vanilla Half-Life 2" : activeTc + "  (-game " + activeTc + ")");

        extraArgs = findViewById(R.id.extra_args);
        extraArgs.setText(prefs.getString("extra_args", "-console"));

        engineEnv = findViewById(R.id.engine_env);
        engineEnv.setText(prefs.getString("engine_env", "LIBGL_USEVBO=0"));

        Button save = findViewById(R.id.save);
        save.setOnClickListener(v -> {
            prefs.edit()
                    .putString("extra_args", extraArgs.getText().toString().trim())
                    .putString("engine_env", engineEnv.getText().toString().trim())
                    .apply();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            finish();
        });

        findViewById(R.id.restore_gi).setOnClickListener(v ->
                Toast.makeText(this, restoreGameinfos(), Toast.LENGTH_LONG).show());

        findViewById(R.id.disable_all).setOnClickListener(v ->
                Toast.makeText(this, disableAllRegular(), Toast.LENGTH_LONG).show());
    }

    /** Copy every srceng/&lt;tc&gt;/gameinfo.txt.orig back over gameinfo.txt. */
    private String restoreGameinfos() {
        int n = 0;
        File root = new File(ModScanner.ROOT);
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) {
                File orig = new File(d, "gameinfo.txt.orig");
                File live = new File(d, "gameinfo.txt");
                if (orig.isFile() && copy(orig, live)) n++;
            }
        }
        return n > 0 ? "Restored " + n + " original gameinfo.txt file(s)"
                : "No gameinfo.txt.orig backups found";
    }

    /** Move every entry of hl2/custom/ into hl2/custom_disabled/. */
    private String disableAllRegular() {
        File custom = new File(ModScanner.ROOT, "hl2/custom");
        File disabled = new File(ModScanner.ROOT, "hl2/custom_disabled");
        File[] entries = custom.listFiles();
        if (entries == null || entries.length == 0)
            return "No regular mods to disable";
        disabled.mkdirs();
        List<String> failed = new ArrayList<>();
        int n = 0;
        for (File f : entries) {
            File to = new File(disabled, f.getName());
            if (to.exists()) { failed.add(f.getName()); continue; }
            if (f.renameTo(to)) n++; else failed.add(f.getName());
        }
        return "Disabled " + n + " mod(s)"
                + (failed.isEmpty() ? "" : " (failed: "
                        + android.text.TextUtils.join(", ", failed) + ")");
    }

    static boolean copy(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
