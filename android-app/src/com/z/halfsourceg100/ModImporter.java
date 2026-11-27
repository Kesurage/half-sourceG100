package com.z.halfsourceg100;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * On-device Tier-2 import (roadmap Phase 3, item 3): turn a PC mod zip into
 * a mounted package under /sdcard/srceng with the SAME KEEP/STRIP/FIX
 * contract as tools/tc_extractor.py (PC twin) and tier2/ (engine twin).
 *
 * Classification for zips works from the entry list (no extraction pass
 * needed); the generated gameinfo ALWAYS comes from the C++ mount planner
 * via JNI — one planner, not three.
 */
final class ModImporter {

    // KEEP/STRIP contract — keep in sync with tools/tc_extractor.py
    static final Set<String> KEEP_DIRS = new HashSet<>(Arrays.asList(
            "maps", "materials", "models", "sound", "resource", "scripts",
            "particles", "cfg", "media", "scenes", "expressions", "actions", "save"));
    static final Set<String> STRIP_DIRS = new HashSet<>(Arrays.asList(
            "bin", "directx", "dxredist", "redist", "__installers", "__support",
            "steam", "uninstall", "vc_redist", "dotnet"));
    static final Set<String> STRIP_EXTS = new HashSet<>(Arrays.asList(
            ".exe", ".dll", ".so", ".dylib", ".bat", ".cmd", ".lnk", ".msi", ".iso", ".url", ".tmp"));
    static final Set<String> ENGINE_ARTIFACTS = new HashSet<>(Arrays.asList(
            "hl2.exe", "srcds.exe", "steam.inf", "hl2_linux", "srcds_linux"));
    static final Set<String> TOP_FILE_EXTS = new HashSet<>(Arrays.asList(
            ".vpk", ".bsp", ".nav", ".ain", ".lst", ".res", ".gameinfo"));
    static final long VPk_SIG = 0x55AA1234L;

    static class Report {
        String modName = "mod";
        String modClass = "EMPTY";
        String mountMode = "none";
        String error;
        int kept, stripped;
        boolean gameinfoGenerated;
        List<String> warnings = new ArrayList<>();
    }

    private ModImporter() {}

    static Report fromZip(Context ctx, Uri uri) {
        Report rep = new Report();
        try {
            // ---- pass 1: entry list + original gameinfo text ---------------
            List<String> names = new ArrayList<>();
            String gameinfoPath = null, gameinfoText = null;
            try (ZipInputStream zis = zip(ctx, uri)) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    if (ze.isDirectory()) continue;
                    names.add(ze.getName());
                    String low = ze.getName().toLowerCase(Locale.ROOT);
                    if (low.endsWith("gameinfo.txt") && slashCount(ze.getName()) <= 1
                            && gameinfoPath == null) {
                        gameinfoPath = ze.getName();
                        gameinfoText = readEntry(zis, 256 * 1024);
                    }
                }
            }
            if (names.isEmpty()) {
                rep.error = "The zip contains no files.";
                return rep;
            }

            String gameDir = parentOf(gameinfoPath); // "" or "MyTC/"
            boolean hasDlls = false, hasSrc = false, hasEngine = false, hasContent = false;
            List<String> vpks = new ArrayList<>();
            for (String n : names) {
                String rel = stripPrefix(n, gameDir);
                if (rel == null || rel.isEmpty()) continue;
                String low = rel.toLowerCase(Locale.ROOT);
                if (low.startsWith("bin/") && (low.endsWith("client.dll")
                        || low.endsWith("server.dll")
                        || low.endsWith("client_episodic.dll")
                        || low.endsWith("server_episodic.dll"))) hasDlls = true;
                if (low.startsWith("src/") && (low.endsWith(".cpp") || low.endsWith(".vcxproj")
                        || low.endsWith(".sln") || low.endsWith(".mak"))) hasSrc = true;
                if (ENGINE_ARTIFACTS.contains(low)) hasEngine = true;
                if (rel.contains("/") && KEEP_DIRS.contains(firstSegment(low))) hasContent = true;
                if (low.endsWith(".vpk")) {
                    hasContent = true;
                    if (!low.matches(".*\\.\\d{3}\\.vpk$")) vpks.add(fileName(rel));
                }
            }

            // classify (mirror of the tier2 decision order)
            String modClass;
            if (hasEngine) modClass = "STANDALONE_TC";
            else if (hasSrc) modClass = "SOURCE_MOD";
            else if (hasDlls) modClass = "DLL_ONLY";
            else if (hasContent) modClass = "CONTENT_ONLY";
            else if (gameinfoPath != null) modClass = "BROKEN_GAMEINFO";
            else modClass = "EMPTY";
            if ("EMPTY".equals(modClass) || "BROKEN_GAMEINFO".equals(modClass)) {
                rep.modClass = modClass;
                rep.error = "Nothing usable in this zip"
                        + (gameinfoPath == null ? " (no gameinfo.txt, no content dirs, no VPKs)."
                                : " (gameinfo present but no content).");
                return rep;
            }

            // mount mode (decide_mount_mode twin)
            boolean tcMode = "STANDALONE_TC".equals(modClass) || "SOURCE_MOD".equals(modClass);

            // name: the GAME dir if the zip nests one, else the zip's display name
            String modName = !gameDir.isEmpty()
                    ? lastSegment(gameDir) : zipDisplayName(ctx, uri);
            modName = sanitizeDir(modName);
            rep.modName = modName;
            rep.modClass = modClass;
            rep.mountMode = tcMode ? "tc" : "addon";

            // metadata from the original gameinfo (display only; mount facts win)
            String game = kvValue(gameinfoText, "game");
            String title = kvValue(gameinfoText, "title");
            if (title == null || title.isEmpty()) title = kvValue(gameinfoText, "title2");
            if (title == null || title.isEmpty()) title = game;
            int appId = kvInt(gameinfoText, "SteamAppId", 220);

            // ---- pass 2: extract with the KEEP/STRIP filter ----------------
            File dest = tcMode
                    ? new File(ModScanner.ROOT, modName)
                    : new File(ModScanner.ROOT, "hl2/custom/" + modName);
            if (dest.exists()) {
                rep.error = "Destination already exists:\n" + dest.getAbsolutePath()
                        + "\n(remove it first or rename the zip)";
                return rep;
            }
            dest.mkdirs();
            String destRoot = dest.getCanonicalPath() + File.separator;

            try (ZipInputStream zis = zip(ctx, uri)) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    if (ze.isDirectory()) continue;
                    String rel = stripPrefix(ze.getName(), gameDir);
                    if (rel == null || rel.isEmpty()) continue;
                    String low = rel.toLowerCase(Locale.ROOT);

                    if (low.equals("gameinfo.txt")) {
                        // original preserved verbatim; the mount planner writes the active one
                        File out = new File(dest, "gameinfo.txt.orig");
                        try (OutputStream os = new FileOutputStream(out)) {
                            pipe(zis, os);
                        }
                        rep.kept++;
                        continue;
                    }
                    if (!keepFile(rel)) {
                        rep.stripped++;
                        continue;
                    }
                    File out = new File(dest, rel);
                    if (!out.getCanonicalPath().startsWith(destRoot)) { // zip-slip guard
                        rep.stripped++;
                        continue;
                    }
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        pipe(zis, os);
                    }
                    rep.kept++;
                }
            }

            // validate root VPKs (same signature check as the extractor)
            File[] rootFiles = dest.listFiles();
            if (rootFiles != null) {
                for (File f : rootFiles) {
                    if (!f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".vpk"))
                        continue;
                    if (!vpkSignatureOk(f)) {
                        if (f.delete()) rep.stripped++;
                        rep.warnings.add("invalid VPK signature (removed): " + f.getName());
                    }
                }
            }

            // ---- FIX: gameinfo for TCs — generated by the C++ planner ------
            if (tcMode) {
                List<String> destVpks = new ArrayList<>();
                File[] fs = dest.listFiles();
                if (fs != null)
                    for (File f : fs)
                        if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".vpk"))
                            destVpks.add(f.getName());
                // rev 5: validated exact-filename base mounts (no wildcards —
                // a wildcard can mount a corrupt VPK and crash the engine)
                List<Tier2Jni.BaseMount> baseMounts = TcFirstAid.enumerateBaseMounts();
                if (Tier2Jni.available()) {
                    Tier2Jni.Result plan = Tier2Jni.plan(modClass, modName,
                            game == null ? modName : game,
                            title == null ? modName : title, 220, destVpks, baseMounts);
                    if (plan.ok) {
                        writeUtf8(new File(dest, "gameinfo.txt"), plan.gameinfoText);
                        rep.gameinfoGenerated = true;
                    } else {
                        rep.warnings.add("mount planner refused this mod (class " + modClass + ")");
                    }
                    if (plan.warnings != null) rep.warnings.addAll(plan.warnings);
                } else {
                    rep.warnings.add("tier2 planner unavailable — gameinfo.txt NOT generated; "
                            + "the folder mounts raw");
                }
            } else {
                rep.warnings.add("addon mode: no gameinfo generated; mounted via the engine's "
                        + "hl2/custom/ scan");
            }

            // class guidance (honest reporting contract)
            if ("DLL_ONLY".equals(modClass))
                rep.warnings.add("DLL_ONLY: mod code will NOT run on ARM64; content is fully "
                        + "mounted and base HL2 code is the fallback");
            else if ("SOURCE_MOD".equals(modClass))
                rep.warnings.add("SOURCE_MOD: Tier-1 native build recommended; extracted content "
                        + "runs with base code until then");
            else if ("STANDALONE_TC".equals(modClass))
                rep.warnings.add("STANDALONE_TC: engine binaries stripped; content extracted");

            // manifest (reproducibility, same spirit as the PC extractor)
            try {
                JSONObject man = new JSONObject();
                man.put("mod", modName);
                man.put("class", modClass);
                man.put("mount_mode", rep.mountMode);
                man.put("kept", rep.kept);
                man.put("stripped", rep.stripped);
                man.put("gameinfo_generated", rep.gameinfoGenerated);
                man.put("warnings", new org.json.JSONArray(rep.warnings));
                writeUtf8(new File(dest, "manifest.json"), man.toString(2));
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            rep.error = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        return rep;
    }

    // ------------------------------------------------------------ filters --

    static boolean keepFile(String rel) {
        String low = rel.toLowerCase(Locale.ROOT);
        String topLow = firstSegment(low);
        String nameLow = fileName(low);
        if (low.startsWith("bin/")) return false;
        if (rel.contains("/") && STRIP_DIRS.contains(topLow)) return false;
        if (ENGINE_ARTIFACTS.contains(low)) return false;
        if (nameLow.equals("gameinfo.txt")) return true;
        int dot = nameLow.lastIndexOf('.');
        String ext = dot >= 0 ? nameLow.substring(dot) : "";
        if (rel.contains("/")) {
            if (KEEP_DIRS.contains(topLow)) return !STRIP_EXTS.contains(ext);
            return ext.equals(".vpk"); // vpk nested in an unknown dir (extractor behavior)
        }
        return TOP_FILE_EXTS.contains(ext);
    }

    static boolean vpkSignatureOk(File f) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            byte[] hdr = new byte[8];
            if (raf.read(hdr) < 8) return false;
            long sig = (hdr[0] & 0xFFL) | (hdr[1] & 0xFFL) << 8 | (hdr[2] & 0xFFL) << 16
                    | (hdr[3] & 0xFFL) << 24;
            long ver = (hdr[4] & 0xFFL) | (hdr[5] & 0xFFL) << 8 | (hdr[6] & 0xFFL) << 16
                    | (hdr[7] & 0xFFL) << 24;
            return sig == VPk_SIG && (ver == 1 || ver == 2);
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------ helpers --

    static ZipInputStream zip(Context ctx, Uri uri) throws IOException {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("cannot open the picked zip");
        return new ZipInputStream(new java.io.BufferedInputStream(in));
    }

    static void pipe(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
    }

    static String readEntry(InputStream in, int cap) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while (total < cap && (n = in.read(buf)) > 0) {
            int take = Math.min(n, cap - total);
            bos.write(buf, 0, take);
            total += take;
        }
        return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    static int slashCount(String s) {
        int c = 0;
        for (char ch : s.toCharArray()) if (ch == '/') c++;
        return c;
    }

    /** "MyTC/gameinfo.txt" -> "MyTC/", "gameinfo.txt" -> "", null -> "" */
    static String parentOf(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i + 1);
    }

    /** Entry name relative to the game dir, or null when outside it. */
    static String stripPrefix(String name, String gameDir) {
        String n = name.replace('\\', '/');
        if (gameDir == null || gameDir.isEmpty()) return n;
        return n.startsWith(gameDir) ? n.substring(gameDir.length()) : null;
    }

    static String firstSegment(String path) {
        int i = path.indexOf('/');
        return i < 0 ? path : path.substring(0, i);
    }

    static String fileName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    static String lastSegment(String path) {
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    static String sanitizeDir(String name) {
        if (name == null) name = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot); // drop .zip
        StringBuilder b = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')
                b.append(c);
            else if (c == ' ' || c == '.') b.append('_');
        }
        String out = b.toString();
        while (out.startsWith("_")) out = out.substring(1);
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        return out.isEmpty() ? "importedmod" : out;
    }

    static String zipDisplayName(Context ctx, Uri uri) {
        try (android.database.Cursor c = ctx.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {
        }
        String p = uri.getLastPathSegment();
        return p == null ? "importedmod" : p;
    }

    /** Tiny gameinfo scan: first quoted-or-bare value for a key. */
    static String kvValue(String text, String key) {
        if (text == null) return null;
        try (Scanner sc = new Scanner(text)) {
            while (sc.hasNextLine()) {
                String line = sc.nextLine();
                int ci = line.indexOf("//");
                if (ci >= 0) line = line.substring(0, ci);
                line = line.trim();
                if (!line.startsWith(key)) continue;
                String rest = line.substring(key.length()).trim();
                if (rest.isEmpty()) continue;
                if (rest.startsWith("\"")) {
                    int end = rest.indexOf('"', 1);
                    if (end > 1) return rest.substring(1, end);
                } else {
                    int end = 0;
                    while (end < rest.length() && !Character.isWhitespace(rest.charAt(end))) end++;
                    if (end > 0) return rest.substring(0, end);
                }
            }
        }
        return null;
    }

    static int kvInt(String text, String key, int def) {
        try {
            String v = kvValue(text, key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    static void writeUtf8(File f, String s) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
