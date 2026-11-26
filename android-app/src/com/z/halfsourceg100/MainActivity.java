package com.z.halfsourceg100;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * Phase 3 launcher home — the mod catalog (roadmap Phase 3, item 1).
 *
 * Two sections, one engine (docs/mount-architecture.md):
 *   TC MODS       srceng/<tc>/            tap to select, launched via -game
 *   REGULAR MODS  srceng/hl2/custom/      tap to enable/disable (moved to
 *                                          custom_disabled/ to turn off)
 *
 * Every entry shows its tier2 class + honest warnings; long-press for the
 * full "what works / what doesn't" report. Import converts a PC mod zip
 * on-device with the same KEEP/STRIP/FIX contract as the PC extractor.
 */
public class MainActivity extends Activity implements ModAdapter.State {

    static final String DEFAULT_ENGINE_PKG = "com.valvesoftware.source"; // nillerusr engine
    static final String REPO_URL = "https://github.com/Kesurage/half-sourceG100";

    private static final int REQ_IMPORT_ZIP = 11;

    private ModScanner.ScanResult scan;
    private ModAdapter tcAdapter, regAdapter;
    private TextView status, logView;
    private Button tabTc, tabReg, launchBtn;
    private boolean showTc = true;
    private boolean busy = false;
    private boolean askedPermission = false;
    private String activeTc = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        logView = findViewById(R.id.log);
        tabTc = findViewById(R.id.tab_tc);
        tabReg = findViewById(R.id.tab_reg);
        launchBtn = findViewById(R.id.btn_launch);

        ListView list = findViewById(R.id.list);
        TextView empty = findViewById(R.id.empty);
        tcAdapter = new ModAdapter(this, true, this);
        regAdapter = new ModAdapter(this, false, this);
        list.setAdapter(tcAdapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((p, v, pos, id) -> onItem(pos));
        list.setOnItemLongClickListener((p, v, pos, id) -> { onItemLong(pos); return true; });

        tabTc.setOnClickListener(v -> setTab(true));
        tabReg.setOnClickListener(v -> setTab(false));
        findViewById(R.id.btn_rescan).setOnClickListener(v -> rescan());
        findViewById(R.id.btn_import).setOnClickListener(v -> pickZip());
        findViewById(R.id.btn_diag).setOnClickListener(v ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
        findViewById(R.id.btn_setup).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        launchBtn.setOnClickListener(v -> launch());

        // status text doubles as the permission button when access is missing
        status.setOnClickListener(v -> {
            if (!storageOk()) askStorage();
        });

        activeTc = getSharedPreferences("launcher", MODE_PRIVATE).getString("active_tc", "");
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkStorage();
    }

    // ------------------------------------------------------------ storage --

    boolean storageOk() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private void checkStorage() {
        if (storageOk()) {
            rescan();
            return;
        }
        status.setText("All-files access is needed to manage the content root:\n"
                + ModScanner.ROOT
                + "\n\nTap here to grant, then allow \"All files access\" for "
                + "half-sourceG100 and return to this screen.");
        if (!askedPermission) {
            askedPermission = true;
            askStorage();
        }
    }

    private void askStorage() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
            }
        }
    }

    // -------------------------------------------------------------- scan --

    void rescan() {
        if (busy) return;
        busy = true;
        status.setText("Scanning " + ModScanner.ROOT + " ...");
        new Thread(() -> {
            final ModScanner.ScanResult r = ModScanner.scan();
            runOnUiThread(() -> {
                scan = r;
                busy = false;
                applyScan();
            });
        }).start();
    }

    private void applyScan() {
        if (!activeTc.isEmpty()) {
            boolean found = false;
            if (scan.tcs != null)
                for (ModScanner.Entry e : scan.tcs)
                    if (e.dirName.equals(activeTc)) { found = true; break; }
            if (!found) setActiveTc("");
        }
        tcAdapter.setItems(scan.tcs);
        regAdapter.setItems(scan.regular);
        setTab(showTc);
        updateStatus();
    }

    void updateStatus() {
        StringBuilder s = new StringBuilder();
        s.append("Content root: ").append(ModScanner.ROOT)
                .append(scan.rootExists ? "" : "  (not found — create it, or use Import)");
        s.append("\nHL2 base content: ")
                .append(scan.hl2Present ? "present" : "MISSING (put your hl2/ copy here)");
        s.append("\nTier2 classifier: ")
                .append(Tier2Jni.available() ? safeVersion() : "unavailable");
        s.append("\nEngine bundle: ")
                .append(EngineJni.available() ? "bundled" : "not bundled in this build");
        s.append("\nExternal engine: ")
                .append(externalEngineInstalled() ? enginePkg() + " (installed)"
                        : enginePkg() + " (not installed)");
        status.setText(s.toString());
        updateLaunchText();
    }

    private String safeVersion() {
        try {
            String v = Tier2Jni.version();
            return v == null ? "loaded" : v;
        } catch (Throwable t) {
            return "loaded";
        }
    }

    String enginePkg() {
        return getSharedPreferences("hsg100", MODE_PRIVATE)
                .getString("engine_pkg", DEFAULT_ENGINE_PKG);
    }

    boolean externalEngineInstalled() {
        try {
            return getPackageManager().getLaunchIntentForPackage(enginePkg()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------- catalog --

    List<ModScanner.Entry> currentList() {
        return showTc ? scan.tcs : scan.regular;
    }

    private void setTab(boolean tc) {
        showTc = tc;
        ((ListView) findViewById(R.id.list)).setAdapter(tc ? tcAdapter : regAdapter);
        tabTc.setEnabled(!tc);
        tabReg.setEnabled(tc);
        updateLaunchText();
    }

    private void updateLaunchText() {
        int enabled = 0;
        if (scan != null && scan.regular != null)
            for (ModScanner.Entry e : scan.regular) if (e.present) enabled++;
        String target = activeTc.isEmpty() ? "Half-Life 2" : activeTc;
        String mods = enabled > 0 ? "  (+" + enabled + (enabled > 1 ? " mods" : " mod") + ")" : "";
        launchBtn.setText("LAUNCH  " + target + mods);
    }

    private void onItem(int pos) {
        if (scan == null) return;
        List<ModScanner.Entry> l = currentList();
        if (pos < 0 || pos >= l.size()) return;
        ModScanner.Entry e = l.get(pos);
        if (e.isTc) {
            setActiveTc(activeTc.equals(e.dirName) ? "" : e.dirName);
        } else {
            toggleRegular(e);
        }
    }

    private void setActiveTc(String dir) {
        activeTc = dir;
        getSharedPreferences("launcher", MODE_PRIVATE).edit().putString("active_tc", dir).apply();
        tcAdapter.notifyDataSetChanged();
        updateLaunchText();
        toast(dir.isEmpty()
                ? "Vanilla Half-Life 2 selected"
                : "TC selected: " + dir + "  (engine runs with -game " + dir + ")");
    }

    private void toggleRegular(ModScanner.Entry e) {
        File custom = new File(ModScanner.ROOT, "hl2/custom");
        File disabledDir = new File(ModScanner.ROOT, "hl2/custom_disabled");
        File to = e.disabled ? new File(custom, e.dirName) : new File(disabledDir, e.dirName);
        if (to.exists()) {
            toast("Name conflict: " + e.dirName + " already exists on the other side");
            return;
        }
        to.getParentFile().mkdirs();
        if (e.file.renameTo(to)) {
            toast((e.disabled ? "Enabled: " : "Disabled: ") + e.dirName);
            rescan();
        } else {
            toast("Could not move " + e.dirName + " (storage permission?)");
        }
    }

    private void onItemLong(int pos) {
        if (scan == null) return;
        List<ModScanner.Entry> l = currentList();
        if (pos < 0 || pos >= l.size()) return;
        ModScanner.Entry e = l.get(pos);
        Tier2Jni.Result info = e.info;

        StringBuilder m = new StringBuilder();
        m.append(e.isTc ? "TC MOD  (srceng/" : "REGULAR MOD  (hl2/custom")
                .append(e.disabled ? "_disabled/" : "/").append(e.dirName).append(")\n\n");
        if (!e.isVpkFile) {
            m.append("Class: ").append(info.modClass).append('\n');
            if (info.game != null && !info.game.isEmpty()) m.append("Game: ").append(info.game).append('\n');
            if (info.title != null && !info.title.isEmpty()) m.append("Title: ").append(info.title).append('\n');
            if (info.steamAppId != 0)
                m.append("SteamAppId: ").append(info.steamAppId)
                        .append("  (re-parented to 220 at mount)\n");
            if (info.lineage != null && !"unknown".equals(info.lineage))
                m.append("Lineage: ").append(info.lineage).append('\n');
            if (info.vpks != null && !info.vpks.isEmpty())
                m.append("VPKs: ").append(info.vpks.size()).append('\n');
            if (e.mapCount > 0) m.append("Maps: ").append(e.mapCount).append('\n');
            if (info.hasWindowsDlls) m.append("Windows DLLs present\n");
            if (info.hasSourceCode) m.append("Source tree present\n");
        }
        m.append("\nWhat works / what doesn't:\n").append(honestReport(e));
        if (info.notes != null && !info.notes.isEmpty()) {
            m.append("\n\nClassifier notes:");
            for (String n : info.notes) m.append("\n\u00b7 ").append(n);
        }
        new AlertDialog.Builder(this)
                .setTitle(e.dirName)
                .setMessage(m.toString())
                .setPositiveButton("Close", null)
                .show();
    }

    static String honestReport(ModScanner.Entry e) {
        switch (e.info.modClass) {
            case "CONTENT_ONLY":
                return "Maps, models, materials, sounds: fully mounted (Tier 2).\n"
                        + "Custom code: none present, nothing to miss.";
            case "SOURCE_MOD":
                return "Content: fully mounted now.\nCode: needs a Tier-1 native ARM64 build "
                        + "(client.so/server.so in the mod's bin/) — until then base HL2 code "
                        + "runs and scripted entities differ.";
            case "DLL_ONLY":
                return "Content: fully mounted.\nCode: closed Windows DLLs do NOT run on ARM64 — "
                        + "base HL2 code is the fallback, so custom NPCs/weapons will be missing "
                        + "(Tier 3 translation is the eventual fix).";
            case "STANDALONE_TC":
                return "This mod shipped its own engine. Run Import (or the PC extractor) first — "
                        + "the content survives, the Windows engine does not.";
            case "BROKEN_GAMEINFO":
            case "EMPTY":
                return "Nothing usable found in this folder.";
            default:
                return "";
        }
    }

    // ------------------------------------------------------------- launch --

    private void launch() {
        if (scan == null) {
            rescan();
            return;
        }
        if (!activeTc.isEmpty()) {
            File gi = new File(ModScanner.ROOT + "/" + activeTc, "gameinfo.txt");
            if (!gi.isFile()) {
                new AlertDialog.Builder(this)
                        .setTitle("TC not ready")
                        .setMessage("srceng/" + activeTc + "/ has no gameinfo.txt.\n\n"
                                + "Use Import to run the Tier-2 extraction on the mod's zip, "
                                + "or place a converted TC folder there.")
                        .setPositiveButton("Close", null)
                        .show();
                return;
            }
        }
        if (EngineJni.available()) {
            Intent it = new Intent(this, EngineActivity.class);
            it.putExtra("game", activeTc.isEmpty() ? "hl2" : activeTc);
            startActivity(it);
        } else if (externalEngineInstalled()) {
            Intent it = getPackageManager().getLaunchIntentForPackage(enginePkg());
            if (it != null) {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            }
            toast("Opened the external engine — pick "
                    + (activeTc.isEmpty() ? "hl2" : activeTc)
                    + " there (it reads the same " + ModScanner.ROOT + " root)");
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("No engine yet")
                    .setMessage("This build is the mod manager (launcher shell). The engine "
                            + "bundle ships as its own release once the Phase-0 engine build "
                            + "lands.\n\nMeanwhile, the nillerusr engine app ("
                            + DEFAULT_ENGINE_PKG + ") reads the same " + ModScanner.ROOT
                            + " root if you install it.")
                    .setPositiveButton("Open releases", (d, w) -> openUrl(REPO_URL + "/releases"))
                    .setNegativeButton("Close", null)
                    .show();
        }
    }

    void openUrl(String u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------- import --

    private void pickZip() {
        if (!storageOk()) {
            checkStorage();
            return;
        }
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        it.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/octet-stream", "application/x-zip-compressed"});
        startActivityForResult(it, REQ_IMPORT_ZIP);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMPORT_ZIP && res == RESULT_OK && data != null && data.getData() != null) {
            importZip(data.getData());
        }
    }

    private void importZip(Uri uri) {
        busy = true;
        status.setText("Importing mod ... (large zips can take a while)");
        new Thread(() -> {
            final ModImporter.Report rep = ModImporter.fromZip(this, uri);
            runOnUiThread(() -> {
                busy = false;
                showReport(rep);
                rescan();
            });
        }).start();
    }

    private void showReport(ModImporter.Report rep) {
        StringBuilder m = new StringBuilder();
        if (rep.error != null) {
            m.append(rep.error);
        } else {
            m.append("Imported: ").append(rep.modName).append('\n')
                    .append("Class: ").append(rep.modClass).append('\n')
                    .append("Mount: ").append(rep.mountMode.equals("tc")
                            ? "TC mod (srceng/" + rep.modName + "/)"
                            : "regular mod (hl2/custom/" + rep.modName + "/)").append('\n')
                    .append("Kept ").append(rep.kept).append(" file(s), stripped ")
                    .append(rep.stripped).append('\n');
            if (rep.gameinfoGenerated)
                m.append("gameinfo.txt generated (engine-valid tokens)\n");
            if (!rep.warnings.isEmpty()) {
                m.append('\n');
                for (String w : rep.warnings) m.append("\u00b7 ").append(w).append('\n');
            }
            logView.setText("Import: " + rep.modName + " -> "
                    + (rep.mountMode.equals("tc") ? "srceng/" : "hl2/custom/")
                    + rep.modName + "  (kept " + rep.kept + ", stripped " + rep.stripped + ")");
            logView.setVisibility(View.VISIBLE);
        }
        new AlertDialog.Builder(this)
                .setTitle(rep.error == null ? "Import complete" : "Import failed")
                .setMessage(m.toString())
                .setPositiveButton("Close", null)
                .show();
    }

    // ------------------------------------------------------------ adapter state

    @Override public boolean isTcSelected(String dirName) {
        return dirName.equals(activeTc);
    }

    @Override public boolean isRegularEnabled(ModScanner.Entry e) {
        return e.present;
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
