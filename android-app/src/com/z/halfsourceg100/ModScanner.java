package com.z.halfsourceg100;

import android.os.Environment;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Scans the dual-root mod tree (docs/mount-architecture.md):
 *
 *   TC mods       srceng/&lt;tc&gt;/           own gameinfo, launched via -game
 *   regular mods  srceng/hl2/custom/      content layers, engine auto-scan
 *   disabled      srceng/hl2/custom_disabled/   (sibling — invisible to the scan)
 *
 * Classification per entry comes from the tier2 C++ library via JNI, so the
 * catalog shows the same class + honest warnings the engine pipeline uses.
 */
public final class ModScanner {

    /** Canonical content root (same root nillerusr's engine and GDroid use). */
    public static final String ROOT =
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/srceng";

    /** Engine-owned first-level dirs under srceng/ — never listed as TC mods. */
    static final String[] RESERVED = {"hl2", "platform", "sourceengine", "bin"};

    public static class Entry {
        public String dirName;       // folder or vpk file name (unique id)
        public boolean isTc;         // TC (true) or regular mod (false)
        public boolean isVpkFile;    // regular mod as a single loose .vpk
        public boolean disabled;     // lives in custom_disabled/
        public boolean present = true; // enabled (in custom/)
        public boolean needsRename;  // TC dir name breaks the engine argv (v0.6.0)
        public Tier2Jni.Result info = new Tier2Jni.Result();
        public File file;
        public long sizeBytes;
        public int mapCount;
    }

    public static class ScanResult {
        public boolean rootExists, hl2Present;
        public List<Entry> tcs = new ArrayList<>();
        public List<Entry> regular = new ArrayList<>();
    }

    private ModScanner() {}

    public static ScanResult scan() {
        ScanResult r = new ScanResult();
        File root = new File(ROOT);
        r.rootExists = root.isDirectory();
        File hl2 = new File(root, "hl2");
        r.hl2Present = hl2.isDirectory() && hl2HasContent(hl2);
        if (!r.rootExists) return r;

        // ---- TC mods: first-level dirs except engine-owned
        for (File f : listSorted(root)) {
            if (!f.isDirectory()) continue;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            boolean reserved = false;
            for (String x : RESERVED) if (n.equalsIgnoreCase(x)) { reserved = true; break; }
            if (reserved) continue;

            Entry e = new Entry();
            e.isTc = true;
            e.dirName = n;
            e.file = f;
            e.needsRename = TcFirstAid.needsRename(n) || TcFirstAid.isReserved(n);
            if (Tier2Jni.available()) e.info = Tier2Jni.classify(f.getAbsolutePath());
            e.sizeBytes = dirSize(f);
            e.mapCount = countMaps(f);
            r.tcs.add(e);
        }

        // ---- regular mods: hl2/custom/* (+ loose vpks); disabled: custom_disabled/*
        scanCustom(new File(hl2, "custom"), false, r);
        scanCustom(new File(hl2, "custom_disabled"), true, r);
        return r;
    }

    /**
     * The engine mounts hl2/ by its VPK set; a bare maps/ dir is not enough
     * (v0.4.0 counted it as "present" and the engine died silently — the
     * exact crash this check now prevents).
     */
    private static boolean hl2HasContent(File hl2) {
        File[] kids = hl2.listFiles();
        if (kids != null)
            for (File f : kids)
                if (f.isFile() && f.getName().toLowerCase().endsWith("_dir.vpk"))
                    return true; // any mounted VPK set = real base content
        // loose content fallback: some old copies ship unpacked
        return new File(hl2, "gameinfo.txt").isFile()
                && new File(hl2, "maps").isDirectory();
    }

    private static void scanCustom(File dir, boolean disabled, ScanResult r) {
        if (!dir.isDirectory()) return;
        for (File f : listSorted(dir)) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            Entry e = new Entry();
            e.isTc = false;
            e.disabled = disabled;
            e.present = !disabled;
            e.dirName = n;
            e.file = f;
            if (f.isFile() && n.toLowerCase().endsWith(".vpk")) {
                e.isVpkFile = true;
                e.info.modClass = "CONTENT_ONLY";
                e.info.title = n.substring(0, n.length() - 4);
                e.sizeBytes = f.length();
            } else if (f.isDirectory()) {
                if (Tier2Jni.available()) e.info = Tier2Jni.classify(f.getAbsolutePath());
                e.sizeBytes = dirSize(f);
                e.mapCount = countMaps(f);
            } else {
                continue;
            }
            r.regular.add(e);
        }
    }

    static File[] listSorted(File d) {
        File[] out = d.listFiles();
        if (out == null) return new File[0];
        Arrays.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** Bounded recursive size (huge TC trees must not ANR the scan). */
    static long dirSize(File d) {
        long total = 0;
        int budget = 20000;
        ArrayDeque<File> q = new ArrayDeque<>();
        q.add(d);
        while (!q.isEmpty() && budget > 0) {
            File f = q.poll();
            File[] kids = f.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (--budget < 0) return total;
                if (k.isDirectory()) q.add(k);
                else total += k.length();
            }
        }
        return total;
    }

    static int countMaps(File d) {
        File maps = new File(d, "maps");
        File[] bsp = maps.listFiles((file, name) -> name.toLowerCase().endsWith(".bsp"));
        return bsp == null ? 0 : bsp.length;
    }
}
