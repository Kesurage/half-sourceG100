package com.z.halfsourceg100;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * Device + environment report. Doubles as the on-device fact sheet for the
 * renderer plan (docs/renderer-risk.md): GLES baseline today, Vulkan/panvk
 * as the Phase 4 experiment, native resolution always.
 */
public class DiagnosticsActivity extends Activity {

    private TextView report;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        report = findViewById(R.id.report);
        findViewById(R.id.btn_refresh).setOnClickListener(v -> run());
        findViewById(R.id.btn_zip_logs).setOnClickListener(v -> shareZip());
        run();
    }

    /** v0.4.2: one tap -> every log zipped and offered to any share target. */
    private void shareZip() {
        new Thread(() -> {
            final File zip = LogStore.zipAll(this);
            runOnUiThread(() -> {
                if (zip == null || !zip.isFile()) {
                    new AlertDialog.Builder(this)
                            .setTitle("Nothing to share")
                            .setMessage("No logs yet — they appear after the "
                                    + "first launch, crash or hang.")
                            .setPositiveButton("Close", null).show();
                    return;
                }
                try {
                    android.net.Uri uri = LogFileProvider.uriFor(zip);
                    android.content.Intent it = new android.content.Intent(
                            android.content.Intent.ACTION_SEND);
                    it.setType("application/zip");
                    it.putExtra(android.content.Intent.EXTRA_STREAM, uri);
                    it.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(android.content.Intent.createChooser(it, "Share logs zip"));
                } catch (Throwable t) {
                    // no FileProvider (should not happen) -> fall back to text
                    new AlertDialog.Builder(this)
                            .setTitle("Share failed")
                            .setMessage("Zip created at " + zip
                                    + " but no app could share it.\n\n" + t)
                            .setPositiveButton("Close", null).show();
                }
            });
        }).start();
    }

    private void run() {
        report.setText("collecting ...");
        new Thread(() -> {
            final StringBuilder s = buildReport();
            runOnUiThread(() -> {
                report.setText(s.toString());
                populateLogs(); // log list needs the UI thread too
            });
        }).start();
    }

    private StringBuilder buildReport() {
        StringBuilder s = new StringBuilder();

        s.append("== App ==\n");
        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            s.append("half-sourceG100 launcher ").append(vn).append('\n');
        } catch (Exception e) {
            s.append("half-sourceG100 launcher\n");
        }
        s.append("Tier2 classifier: ")
                .append(Tier2Jni.available() ? safeTier2Version() : "unavailable").append('\n');
        s.append("Engine: ")
                .append(EngineHost.bundled(this) ? "bundled (old-port GLES2, armeabi-v7a)"
                        : "not bundled (launcher-only build)").append('\n');
        s.append("32-bit app support (engine requirement): ")
                .append(EngineHost.deviceAbiOk() ? "yes" : "NO — engine cannot run")
                .append('\n');
        s.append("SDL glue: ")
                .append(EngineHost.sdlPresent() ? "present" : "missing").append("\n\n");

        s.append("== Device ==\n");
        s.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        s.append("Board: ").append(Build.BOARD).append("  Hardware: ")
                .append(Build.HARDWARE).append('\n');
        if (Build.VERSION.SDK_INT >= 31)
            s.append("SoC: ").append(Build.SOC_MANUFACTURER).append(' ')
                    .append(Build.SOC_MODEL).append('\n');
        s.append("Android ").append(Build.VERSION.RELEASE).append(" (API ")
                .append(Build.VERSION.SDK_INT).append(")\n");
        s.append("Target device: Infinix Hot 50 4G (Helio G100, Mali-G57 MC2)\n\n");

        s.append("== Display (native resolution policy) ==\n");
        try {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            s.append(String.format(java.util.Locale.ROOT,
                    "Panel: %d x %d @ %ddpi (density %.2f)\n",
                    dm.widthPixels, dm.heightPixels, dm.densityDpi, dm.density));
        } catch (Exception e) {
            s.append("Panel: unknown\n");
        }
        s.append("Policy: RENDER AT NATIVE RESOLUTION ALWAYS.\n")
                .append("No downscaling, no render-scale, no dynamic res.\n\n");

        s.append("== Graphics ==\n");
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            s.append("OpenGL ES: ")
                    .append(am.getDeviceConfigurationInfo().getGlEsVersion()).append('\n');
        } catch (Exception e) {
            s.append("OpenGL ES: unknown\n");
        }
        PackageManager pm = getPackageManager();
        String[] vk = {
                "android.hardware.vulkan.version",
                "android.hardware.vulkan.version.1.1",
                "android.hardware.vulkan.version.1.3",
                "android.hardware.vulkan.compute",
                "android.hardware.vulkan.level",
        };
        for (String f : vk)
            s.append(f).append(": ").append(has(pm, f)).append('\n');
        s.append('\n');

        s.append("== Renderer plan ==\n");
        s.append("Shipping renderer: GLES2 (bundled old-port, Regal).\n");
        s.append("Phase 3 target: Vulkan via panvk on the stock kbase\n");
        s.append("kernel (no root) - see docs/PHASE3-VULKAN.md.\n");
        s.append("Native resolution always.\n\n");

        s.append("== Storage ==\n");
        s.append("All-files access: ").append(android.os.Environment
                .isExternalStorageManager() ? "granted" : "MISSING").append('\n');
        s.append("Content root: ").append(ModScanner.ROOT).append('\n');
        android.os.StatFs st = null;
        try {
            st = new android.os.StatFs(ModScanner.ROOT);
        } catch (Exception ignored) {
        }
        if (st != null)
            s.append("Free: ").append(human(st.getAvailableBytes())).append(" / ")
                    .append(human(st.getTotalBytes())).append('\n');
        s.append('\n');

        s.append("== Mod scan ==\n");
        ModScanner.ScanResult r = ModScanner.scan();
        s.append("Root: ").append(r.rootExists ? "found" : "NOT FOUND").append('\n');
        s.append("HL2 base content: ").append(r.hl2Present ? "present" : "MISSING").append('\n');
        s.append("TC mods: ").append(r.tcs.size());
        for (ModScanner.Entry e : r.tcs)
            s.append("\n  \u00b7 ").append(e.dirName).append(" [").append(e.info.modClass).append(']');
        s.append('\n');
        s.append("Regular mods: ").append(r.regular.size()).append(" (")
                .append(countEnabled(r.regular)).append(" enabled)");
        for (ModScanner.Entry e : r.regular)
            s.append("\n  \u00b7 ").append(e.dirName).append(" [").append(e.info.modClass)
                    .append(e.disabled ? ", disabled" : "").append(']');
        s.append('\n');
        return s;
    }

    // ----------------------------------------------------------- log viewer --

    private void populateLogs() {
        LinearLayout list = findViewById(R.id.log_list);
        list.removeAllViews();
        java.util.List<LogStore.LogEntry> logs = LogStore.listLogs(this);
        if (logs.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("(no logs yet — they appear after the first launch/crash)");
            none.setTextSize(11f);
            none.setTextColor(0xFF8B94A7);
            list.addView(none);
            return;
        }
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat(
                "MM-dd HH:mm", java.util.Locale.ROOT);
        for (LogStore.LogEntry e : logs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            TextView label = new TextView(this);
            label.setText(e.label + "\n" + e.file.getName() + "  \u00b7  "
                    + df.format(new java.util.Date(e.file.lastModified()))
                    + "  \u00b7  " + human(e.file.length()));
            label.setTextSize(12f);
            label.setTextColor(0xFFECEFF4);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(lp);
            row.addView(label);

            android.widget.Button open = new android.widget.Button(this);
            open.setText("View");
            open.setOnClickListener(v -> showLog(e));
            row.addView(open);

            android.widget.Button share = new android.widget.Button(this);
            share.setText("Share");
            share.setOnClickListener(v -> shareLog(e));
            row.addView(share);

            list.addView(row);
        }
    }

    private void showLog(LogStore.LogEntry e) {
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setMessage(LogStore.tail(e.file))
                .setPositiveButton("Close", null)
                .setNeutralButton("Share", (d, w) -> shareLog(e))
                .show();
    }

    private void shareLog(LogStore.LogEntry e) {
        try {
            android.content.Intent it = new android.content.Intent(
                    android.content.Intent.ACTION_SEND);
            it.setType("text/plain");
            it.putExtra(android.content.Intent.EXTRA_TEXT,
                    LogStore.tail(e.file));
            it.putExtra(android.content.Intent.EXTRA_SUBJECT,
                    "half-sourceG100 log: " + e.file.getName());
            startActivity(android.content.Intent.createChooser(it, "Share log"));
        } catch (Throwable ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int countEnabled(List<ModScanner.Entry> l) {
        int n = 0;
        for (ModScanner.Entry e : l) if (e.present) n++;
        return n;
    }

    private boolean has(PackageManager pm, String feature) {
        try {
            return pm.hasSystemFeature(feature);
        } catch (Exception e) {
            return false;
        }
    }

    private String safeTier2Version() {
        try {
            String v = Tier2Jni.version();
            return v == null ? "loaded" : v;
        } catch (Throwable t) {
            return "loaded";
        }
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return bytes / 1024 + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT,
                "%.0f MB", bytes / 1048576.0);
        return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
    }
}
