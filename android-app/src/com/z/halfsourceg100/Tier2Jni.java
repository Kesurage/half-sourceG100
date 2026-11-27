package com.z.halfsourceg100;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * JNI bridge to the tier2 mod-compatibility library — the SAME C++ code the
 * engine uses (tier2/src/*.cpp compiled into libtier2jni.so). One classifier,
 * one mount planner; the launcher never re-implements them.
 */
public final class Tier2Jni {
    static {
        try {
            System.loadLibrary("tier2jni");
        } catch (Throwable t) {
            // classifier unavailable (lib missing) — the UI degrades to raw listings
        }
    }

    private Tier2Jni() {}

    public static native String classifyDir(String path);

    /** Mount-planner entry point: returns JSON {ok, gameinfoText, warnings[]}. */
    public static native String planGameinfo(String modClass, String modDir,
                                             String game, String title,
                                             int steamAppId, String[] vpks,
                                             String[] baseGameDirs, String[] baseVpks);

    /**
     * One base-game content location measured on device: the game dir
     * ("hl2", "episodic", "ep2", "platform") and its VALIDATED VPK
     * filenames (exact names, tree-walked; chunks and corrupt archives
     * already excluded by the caller).
     */
    public static class BaseMount {
        public final String gameDir;
        public final List<String> vpks;
        public BaseMount(String gameDir, List<String> vpks) {
            this.gameDir = gameDir;
            this.vpks = vpks;
        }
    }

    public static native String version();

    public static boolean available() {
        try {
            version();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Classification result (fields mirror hsg100::ModInfo). */
    public static class Result {
        public String modClass = "EMPTY";
        public String game = "";
        public String title = "";
        public String lineage = "";
        public int steamAppId;
        public boolean hasWindowsDlls, hasSourceCode, hasStandaloneEngine;
        public ArrayList<String> vpks = new ArrayList<>();
        public ArrayList<String> notes = new ArrayList<>();
        // plan fields
        public boolean ok;
        public String gameinfoText = "";
        public ArrayList<String> warnings = new ArrayList<>();
    }

    public static Result classify(String path) {
        Result r = new Result();
        String json;
        try {
            json = classifyDir(path);
        } catch (Throwable t) {
            return r;
        }
        if (json == null) return r;
        try {
            JSONObject o = new JSONObject(json);
            r.modClass = o.optString("class", "EMPTY");
            r.game = o.optString("game", "");
            r.title = o.optString("title", "");
            r.lineage = o.optString("lineage", "");
            r.steamAppId = o.optInt("steamAppId", 0);
            r.hasWindowsDlls = o.optBoolean("hasWindowsDlls", false);
            r.hasSourceCode = o.optBoolean("hasSourceCode", false);
            r.hasStandaloneEngine = o.optBoolean("hasStandaloneEngine", false);
            JSONArray a = o.optJSONArray("vpks");
            if (a != null) for (int i = 0; i < a.length(); i++) r.vpks.add(a.getString(i));
            JSONArray n = o.optJSONArray("notes");
            if (n != null) for (int i = 0; i < n.length(); i++) r.notes.add(n.getString(i));
        } catch (Exception ignored) {
        }
        return r;
    }

    public static Result plan(String modClass, String modDir, String game, String title,
                              int appId, List<String> vpks) {
        return plan(modClass, modDir, game, title, appId, vpks, null);
    }

    /**
     * Rev 5: plan with the validated base-game VPK inventory. baseMounts may
     * be null (loose dirs only — the pre-rev-5 emission shape minus the
     * wildcards). The JNI crossing uses parallel arrays with an empty string
     * as the game-dir separator: [hl2, v0, v1, "", episodic, v0, "", ...].
     */
    public static Result plan(String modClass, String modDir, String game, String title,
                              int appId, List<String> vpks, List<BaseMount> baseMounts) {
        Result r = new Result();
        String json;
        String[] dirs = null, baseVpks = null;
        if (baseMounts != null && !baseMounts.isEmpty()) {
            List<String> d = new ArrayList<>();
            List<String> v = new ArrayList<>();
            for (BaseMount bm : baseMounts) {
                if (bm == null || bm.gameDir == null || bm.gameDir.isEmpty()) continue;
                d.add(bm.gameDir);
                if (bm.vpks != null) v.addAll(bm.vpks);
                v.add(""); // separator: this game dir's run ends here
            }
            if (!d.isEmpty()) {
                dirs = d.toArray(new String[0]);
                baseVpks = v.toArray(new String[0]);
            }
        }
        try {
            json = planGameinfo(modClass, modDir, game, title, appId,
                    vpks.toArray(new String[0]), dirs, baseVpks);
        } catch (Throwable t) {
            return r;
        }
        if (json == null) return r;
        try {
            JSONObject o = new JSONObject(json);
            r.ok = o.optBoolean("ok", false);
            r.gameinfoText = o.optString("gameinfoText", "");
            JSONArray w = o.optJSONArray("warnings");
            if (w != null) for (int i = 0; i < w.length(); i++) r.warnings.add(w.getString(i));
        } catch (Exception ignored) {
        }
        return r;
    }
}
