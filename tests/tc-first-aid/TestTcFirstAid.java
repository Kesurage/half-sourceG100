package com.z.halfsourceg100;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Regression tests for the v0.6.0 TC first aid — the layer that makes
 * hand-dropped TC mods (Minerva: Metastasis and friends) actually launch:
 *
 *   1. argv-safe dir names  (the engine's Android entry tokenizes argv with
 *      strtok(argv, " ") — spaces in -game kill the engine with a fatal
 *      missing-gameinfo Error; base games never have spaces, TCs often do)
 *   2. gameinfo regeneration (PC-original search paths -> rev-3 engine-valid
 *      tokens, original preserved as gameinfo.pc.txt) via the tier2 planner,
 *      with the Java fallback path, and with tier2 missing entirely
 *   3. VPK mount-name rules and Ep1/Ep2 fallback lines
 *
 * Plain javac + java, no JUnit — same spirit as tests/test_tc_extractor.py.
 * Run: tests/tc-first-aid/run.sh
 */
public class TestTcFirstAid {

    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("hsg100-test");
        Path srceng = root.resolve("srceng");
        Files.createDirectories(srceng);
        System.setProperty("test.root", srceng.toString());

        needsRenameTable();
        safeNameTable();
        vpkMountNameTable();
        kvParsing();

        // tier2 planner OFF -> Java fallback generator
        System.clearProperty("test.planner");
        System.clearProperty("test.tier2");
        gameinfoRegen(srceng, "fallback");

        // tier2 planner ON -> C++-shaped output, same treatment
        System.setProperty("test.planner", "on");
        gameinfoRegen(srceng, "planner");

        // classifier/planner missing entirely -> still works via fallback
        System.setProperty("test.planner", "off");
        System.setProperty("test.tier2", "off");
        gameinfoRegen(srceng, "no-tier2");

        System.clearProperty("test.planner");
        System.clearProperty("test.tier2");
        episodicLines(srceng);
        prepareRenameFlow(srceng);
        prepareIdempotent(srceng);
        missingGameinfoGenerated(srceng);
        emptyFolderFailsCleanly(srceng);

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        // cleanup temp tree
        Files.walk(root).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------ tables --

    static void needsRenameTable() {
        // base games and already-safe names launch untouched
        ok(!TcFirstAid.needsRename("hl2"), "hl2 needs no rename");
        ok(!TcFirstAid.needsRename("minerva"), "minerva needs no rename");
        ok(!TcFirstAid.needsRename("co-op"), "co-op needs no rename");
        ok(!TcFirstAid.needsRename("EntropyZero2"), "EntropyZero2 needs no rename");
        // THE bug: spaces
        ok(TcFirstAid.needsRename("Minerva Metastasis"), "space name needs rename");
        // switch/command injection shapes
        ok(TcFirstAid.needsRename("-game"), "leading dash needs rename");
        ok(TcFirstAid.needsRename("+quit"), "leading plus needs rename");
        ok(TcFirstAid.needsRename(".."), "dot-dot needs rename");
        ok(TcFirstAid.needsRename(""), "empty needs rename");
        ok(TcFirstAid.needsRename(null), "null needs rename");
        // unicode / punctuation
        ok(TcFirstAid.needsRename("\u041c\u043e\u0434"), "non-ascii needs rename");
        ok(TcFirstAid.needsRename("HL2: Update"), "colon needs rename");
        // reserved roots are engine-owned (argv-safe, but not a valid TC dir)
        ok(TcFirstAid.isReserved("platform"), "platform is reserved");
        ok(TcFirstAid.isReserved("HL2"), "reserved match is case-insensitive");
        ok(!TcFirstAid.isReserved("hl2_update"), "non-reserved name passes");
    }

    static void safeNameTable() {
        eq(TcFirstAid.safeName("Minerva Metastasis"), "minerva_metastasis",
                "Minerva Metastasis -> minerva_metastasis");
        eq(TcFirstAid.safeName("Entropy : Zero 2"), "entropy_zero_2",
                "punctuation runs collapse to one underscore");
        eq(TcFirstAid.safeName("HL2: Update"), "hl2_update", "colon becomes underscore");
        eq(TcFirstAid.safeName("-MyMod"), "mymod", "leading dash stripped");
        eq(TcFirstAid.safeName(".."), "tc", "dot-dots degrade to tc");
        eq(TcFirstAid.safeName("\u041c\u043e\u0434"), "tc", "pure non-ascii degrades to tc");
        eq(TcFirstAid.safeName("platform"), "platform_mod", "reserved gets _mod suffix");
        eq(TcFirstAid.safeName("Black Mesa (2012)"), "black_mesa_2012",
                "parens become underscores");
        eq(TcFirstAid.safeName("Underhell  Chapter 1"), "underhell_chapter_1",
                "double spaces collapse");
    }

    static void vpkMountNameTable() {
        eq(TcFirstAid.vpkMountName("minerva_dir.vpk"), "minerva.vpk", "_dir stripped");
        eq(TcFirstAid.vpkMountName("pak01_dir.vpk"), "pak01.vpk", "pak _dir stripped");
        eq(TcFirstAid.vpkMountName("pak01.003.vpk"), "", "numbered chunk skipped");
        eq(TcFirstAid.vpkMountName("pak01.013.vpk"), "", "3-digit chunk skipped");
        eq(TcFirstAid.vpkMountName("readme.txt"), "", "non-vpk skipped");
        eq(TcFirstAid.vpkMountName("loose.vpk"), "loose.vpk", "plain vpk kept");
        eq(TcFirstAid.vpkMountName(null), "", "null skipped");
    }

    static void kvParsing() {
        String gi = "\"GameInfo\"\n{\n"
                + "\tgame \t \"Minerva: Metastasis\"\n"
                + "\ttitle \"MINERVA: METASTASIS\" // comment\n"
                + "\ttype singleplayer_only\n"
                + "\tdeveloper \"Adam Foster\"\n"
                + "\tFileSystem\n\t{\n"
                + "\t\tSteamAppId 380\n"
                + "\t\tSearchPaths\n\t\t{\n"
                + "\t\t\tGame\t\t\t|gameinfo_path|.\n"
                + "\t\t\tGame\t\t\thl2\n"
                + "\t\t}\n\t}\n}\n";
        eq(TcFirstAid.kvValue(gi, "game"), "Minerva: Metastasis", "kv game (quoted, tabbed)");
        eq(TcFirstAid.kvValue(gi, "title"), "MINERVA: METASTASIS", "kv title (comment stripped)");
        eq(TcFirstAid.kvValue(gi, "type"), "singleplayer_only", "kv type (bare value)");
        eq(TcFirstAid.kvValue(gi, "SteamAppId"), "380", "kv SteamAppId (bare, nested)");
        eq(TcFirstAid.kvType(gi), "singleplayer_only", "kvType valid");
        ok(TcFirstAid.kvType("\"GameInfo\"{ game x }") == null, "kvType null when absent");
        eq(TcFirstAid.kvType("\"GameInfo\"{ type coop_only }"), null,
                "kvType null on unknown value");
    }

    // --------------------------------------------------------- scenarios --

    /** A hand-dropped PC TC folder gets an engine-valid gameinfo. */
    static void gameinfoRegen(Path srceng, String mode) throws Exception {
        Path tc = srceng.resolve("regen_" + mode);
        Files.createDirectories(tc.resolve("maps"));
        Files.write(tc.resolve("gameinfo.txt"), (
                "\"GameInfo\"\n{\n"
                + "\tgame \"Minerva: Metastasis\"\n"
                + "\ttitle \"MINERVA: METASTASIS\"\n"
                + "\ttype singleplayer_only\n"
                + "\tFileSystem\n\t{\n"
                + "\t\tSteamAppId 380\n"
                + "\t\tSearchPaths\n\t\t{\n"
                + "\t\t\tGame\t\t\t|gameinfo_path|.\n"
                + "\t\t\tGame\t\t\t../hl2\n"
                + "\t\t}\n\t}\n}\n").getBytes(StandardCharsets.UTF_8));

        String summary = TcFirstAid.fixGameinfo(tc.toFile());
        ok(summary != null, mode + ": gameinfo rewritten (summary returned)");
        String out = read(tc.resolve("gameinfo.txt"));

        ok(out.contains("generated by half-sourceG100 mount planner"), mode + ": marker present");
        ok(out.contains("|gameinfo_path|."), mode + ": mod dir mounted first");
        ok(out.contains("Game\t\thl2\n"), mode + ": relative hl2 fallback present");
        ok(out.contains("|all_source_engine_paths|hl2\n"), mode + ": token hl2 fallback present");
        ok(out.contains("|all_source_engine_paths|platform"), mode + ": platform mounted last");
        ok(!out.contains("../hl2"), mode + ": PC relative path gone");
        ok(out.contains("\"220\""), mode + ": SteamAppId re-parented to 220");
        ok(out.contains("game \"Minerva: Metastasis\""), mode + ": original game title kept");
        ok(out.indexOf("|gameinfo_path|.") < out.indexOf("Game\t\thl2"),
                mode + ": mod dir before hl2 (priority order)");
        // the original is preserved
        String backup = read(tc.resolve("gameinfo.pc.txt"));
        ok(backup.contains("../hl2") && backup.contains("380"),
                mode + ": original preserved as gameinfo.pc.txt");
        // second run is a no-op (ours already, no episodic dirs in this tree)
        String again = TcFirstAid.fixGameinfo(tc.toFile());
        ok(again == null, mode + ": second run is a no-op");
        ok(read(tc.resolve("gameinfo.txt")).equals(out), mode + ": file unchanged on rerun");
    }

    /** Ep1/Ep2 folders in the root -> fallback search paths appear. */
    static void episodicLines(Path srceng) throws Exception {
        Path tc = srceng.resolve("epline");
        Files.createDirectories(tc);
        Files.write(tc.resolve("gameinfo.txt"),
                TcFirstAid.fallbackGameinfo("epline", "epline", "epline",
                        new ArrayList<String>()).getBytes(StandardCharsets.UTF_8));

        // no episodic content -> untouched
        String s0 = TcFirstAid.fixGameinfo(tc.toFile());
        ok(s0 == null, "episodic: no-op without episodic/ep2 dirs");
        String out0 = read(tc.resolve("gameinfo.txt"));
        ok(!out0.contains("episodic"), "episodic: no lines without content");

        // both dirs present -> both fallbacks inserted before platform
        Files.createDirectories(srceng.resolve("episodic"));
        Files.createDirectories(srceng.resolve("ep2"));
        String s1 = TcFirstAid.fixGameinfo(tc.toFile());
        ok(s1 != null && s1.contains("Ep1/Ep2"), "episodic: summary reports the addition");
        String out1 = read(tc.resolve("gameinfo.txt"));
        ok(out1.contains("Game\t\tepisodic\n")
                && out1.contains("|all_source_engine_paths|episodic"),
                "episodic: both forms present");
        ok(out1.contains("Game\t\tep2\n") && out1.contains("|all_source_engine_paths|ep2"),
                "ep2: both forms present");
        ok(out1.indexOf("|all_source_engine_paths|episodic")
                < out1.indexOf("|all_source_engine_paths|platform"),
                "episodic: inserted before platform");
        // idempotent from here
        ok(TcFirstAid.fixGameinfo(tc.toFile()) == null, "episodic: second run no-op");
    }

    /** The full launch path: rename + regen in one prepare() call. */
    static void prepareRenameFlow(Path srceng) throws Exception {
        Path tc = srceng.resolve("Minerva Metastasis");
        Files.createDirectories(tc.resolve("maps"));
        Files.write(tc.resolve("gameinfo.txt"), (
                "\"GameInfo\"\n{\n\tgame \"Minerva: Metastasis\"\n"
                + "\tFileSystem\n\t{\n\t\tSteamAppId 380\n"
                + "\t\tSearchPaths\n\t\t{\n"
                + "\t\t\tGame\t\t\t|gameinfo_path|.\n"
                + "\t\t\tGame\t\t\t../hl2\n\t\t}\n\t}\n}\n"
        ).getBytes(StandardCharsets.UTF_8));

        TcFirstAid.Result r = TcFirstAid.prepare("Minerva Metastasis");
        ok(r.failReason == null, "prepare: no failure");
        eq(r.dirName, "minerva_metastasis", "prepare: folder renamed argv-safe");
        eq(r.renamedFrom, "Minerva Metastasis", "prepare: rename reported");
        ok(r.gameinfoSummary != null, "prepare: gameinfo summary present");
        ok(!Files.exists(tc), "prepare: old folder gone");
        Path neu = srceng.resolve("minerva_metastasis");
        ok(Files.exists(neu.resolve("gameinfo.txt")), "prepare: gameinfo at new location");
        ok(Files.exists(neu.resolve("gameinfo.pc.txt")), "prepare: backup at new location");
        ok(Files.exists(neu.resolve("maps")), "prepare: content moved with the folder");

        // relaunch under the new name: everything is a no-op now
        TcFirstAid.Result r2 = TcFirstAid.prepare("minerva_metastasis");
        ok(r2.failReason == null && r2.renamedFrom == null && r2.gameinfoSummary == null,
                "prepare: relaunch is a clean no-op");
    }

    /** prepare() is safe to run repeatedly (engine launches, user returns). */
    static void prepareIdempotent(Path srceng) throws Exception {
        Path tc = srceng.resolve("idem");
        Files.createDirectories(tc);
        Files.write(tc.resolve("gameinfo.txt"),
                TcFirstAid.fallbackGameinfo("idem", "idem", "idem",
                        new ArrayList<String>()).getBytes(StandardCharsets.UTF_8));
        // first run: at most the v0.6.0 episodic upgrade (dirs exist by now)
        TcFirstAid.Result a = TcFirstAid.prepare("idem");
        // after that: stable — never rewritten again
        TcFirstAid.Result b = TcFirstAid.prepare("idem");
        TcFirstAid.Result c = TcFirstAid.prepare("idem");
        ok(a.failReason == null && b.failReason == null && c.failReason == null,
                "idempotent: all runs succeed");
        ok(b.gameinfoSummary == null && c.gameinfoSummary == null,
                "idempotent: no rewrites after the first stabilization");
    }

    /** A content folder without any gameinfo gets one generated. */
    static void missingGameinfoGenerated(Path srceng) throws Exception {
        Path tc = srceng.resolve("loosemod");
        Files.createDirectories(tc.resolve("maps"));
        Files.write(tc.resolve("maps/metastasis1.bsp"), new byte[]{1, 2, 3});

        TcFirstAid.Result r = TcFirstAid.prepare("loosemod");
        ok(r.failReason == null, "loose: no failure");
        ok(r.gameinfoSummary != null && r.gameinfoSummary.contains("generated"),
                "loose: gameinfo generated");
        String out = read(tc.resolve("gameinfo.txt"));
        ok(out.contains("game \"loosemod\""), "loose: named after the folder");
        ok(out.contains("|gameinfo_path|."), "loose: mod dir mounted");
        ok(!Files.exists(tc.resolve("gameinfo.pc.txt")), "loose: no backup for a missing file");
    }

    /** An empty/nonexistent folder fails with a readable reason, never a launch. */
    static void emptyFolderFailsCleanly(Path srceng) throws Exception {
        Files.createDirectories(srceng.resolve("emptymod"));
        TcFirstAid.Result r = TcFirstAid.prepare("emptymod");
        ok(r.failReason != null, "empty: fails with a reason");
        ok(r.failReason.contains("not a mod"), "empty: reason says not a mod");

        TcFirstAid.Result r2 = TcFirstAid.prepare("ghost_mod");
        ok(r2.failReason != null, "ghost: fails with a reason");
    }

    // ------------------------------------------------------------ helpers --

    static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    static void ok(boolean cond, String what) {
        if (cond) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }

    static void eq(String actual, String expected, String what) {
        boolean hit = expected == null ? actual == null : expected.equals(actual);
        if (hit) { passed++; System.out.println("  ok   " + what); }
        else {
            failed++;
            System.out.println("  FAIL " + what + "  (expected <" + expected
                    + "> got <" + actual + ">)");
        }
    }
}
