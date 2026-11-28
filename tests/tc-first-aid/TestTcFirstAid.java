package com.z.halfsourceg100;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
        vpkValidator(srceng);
        spliceAndSeed(srceng);
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
        // rev 4: REAL filenames — _dir suffix KEPT (the engine opens the
        // exact name given; a stripped name mounts nothing, silently)
        eq(TcFirstAid.vpkMountName("minerva_dir.vpk"), "minerva_dir.vpk", "_dir kept");
        eq(TcFirstAid.vpkMountName("pak01_dir.vpk"), "pak01_dir.vpk", "pak _dir kept");
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
        ok(out.contains("rev 7"), mode + ": rev-7 emission");
        ok(out.contains("|gameinfo_path|."), mode + ": mod dir mounted first");
        ok(out.contains("Game\t\thl2\n"), mode + ": relative hl2 fallback present");
        ok(out.contains("|all_source_engine_paths|hl2\n"), mode + ": hl2 loose dir mounted");
        ok(!out.contains("*.vpk"), mode + ": NO wildcard mounts (the v0.6.1 FORTIFY abort class)");
        ok(!out.contains("hl2_textures.vpk\n"), mode + ": no stripped-name base mounts (the v0.6.0 black-screen bug)");
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

    /**
     * Rev-7 emission: loose base dirs unconditionally (missing folders
     * contribute nothing), NO wildcards, exact-filename VPK mounts only
     * (spliced from hl2's gameinfo when available, validated inventory as
     * the fallback), and a legacy rev-3/rev-6 gameinfo is upgraded on sight.
     */
    static void episodicLines(Path srceng) throws Exception {
        Path tc = srceng.resolve("epline");
        Files.createDirectories(tc);
        Files.write(tc.resolve("gameinfo.txt"),
                TcFirstAid.fallbackGameinfo("epline", "epline", "epline",
                        new ArrayList<String>(), new ArrayList<>()).getBytes(StandardCharsets.UTF_8));

        // rev-7 file: already current — no-op, loose episodic lines already in
        String s0 = TcFirstAid.fixGameinfo(tc.toFile());
        ok(s0 == null, "episodic: no-op for a rev-7 file");
        String out0 = read(tc.resolve("gameinfo.txt"));
        ok(out0.contains("|all_source_engine_paths|episodic\n")
                && out0.contains("|all_source_engine_paths|ep2\n"),
                "episodic: loose lines present without any content dirs");
        ok(!out0.contains("*.vpk"), "episodic: no wildcard mounts in a fresh emission");

        // legacy rev-3 file (marker but old rev): upgraded to the rev-6 form
        Path old = srceng.resolve("legacy3");
        Files.createDirectories(old);
        Files.write(old.resolve("gameinfo.txt"), (
                "\"gameinfo\"\n{\n"
                + "\t// generated by half-sourceG100 mount planner (Tier 2, rev 3)\n"
                + "\tgame \"Legacy\"\n"
                + "\n\tfilesystem\n\t{\n\n\t\tSearchPaths\n\t\t{\n"
                + "\t\t\tGame\t\t|gameinfo_path|.\n"
                + "\t\t\tGame\t\thl2\n"
                + "\t\t\tGame\t\t|all_source_engine_paths|hl2/hl2_textures.vpk\n"
                + "\t\t\tGame\t\t|all_source_engine_paths|platform\n"
                + "\t\t}\n\t}\n}\n").getBytes(StandardCharsets.UTF_8));
        String s1 = TcFirstAid.fixGameinfo(old.toFile());
        ok(s1 != null && s1.contains("v0.6.4"),
                "episodic: legacy rev-3 file reported as upgraded");
        String out1 = read(old.resolve("gameinfo.txt"));
        ok(out1.contains("rev 7"), "episodic: upgraded file is rev 7");
        ok(out1.contains("|all_source_engine_paths|hl2\n"),
                "episodic: upgraded file has the hl2 loose mount");
        ok(!out1.contains("*.vpk"), "episodic: upgraded file has no wildcards");
        ok(!out1.contains("hl2_textures.vpk\n"),
                "episodic: upgraded file has no stripped-name mounts");
        // the original (broken rev 3) is preserved for the user
        ok(read(old.resolve("gameinfo.pc.txt")).contains("rev 3"),
                "episodic: legacy original preserved as gameinfo.pc.txt");
        // idempotent from here
        ok(TcFirstAid.fixGameinfo(old.toFile()) == null, "episodic: second run no-op");
    }

    /**
     * v0.6.4 (rev 7): base mounts SPLICED from the device's own
     * hl2/gameinfo.txt — the proven-working list — and the TC's config.cfg
     * seeded from hl2's on first launch.
     */
    static void spliceAndSeed(Path srceng) throws Exception {
        // -- a real-looking hl2 with a gameinfo listing exact VPK names --
        Path hl2 = srceng.resolve("hl2");
        Files.createDirectories(hl2);
        Files.createDirectories(hl2.resolve("cfg"));
        Files.write(hl2.resolve("cfg/config.cfg").toFile().toPath(),
                ("unbindall\nbind ESCAPE \"escape\"\nmat_vsync 1\n").getBytes(StandardCharsets.UTF_8));
        // empty stand-in VPK files — the splice only checks existence
        for (String v : new String[]{"hl2_textures_dir.vpk", "hl2_misc_dir.vpk",
                "hl2_sound_misc_dir.vpk", "hl2_pak_dir.vpk"})
            Files.write(hl2.resolve(v).toFile().toPath(), new byte[0]);
        Files.write(hl2.resolve("gameinfo.txt").toFile().toPath(), (
                "\"GameInfo\"\n{\n"
                + "\tgame \"HALF-LIFE 2\"\n"
                + "\tFileSystem\n\t{\n"
                + "\t\tSteamAppId 220\n"
                + "\t\tSearchPaths\n\t\t{\n"
                + "\t\t\tGame\t\t\t|gameinfo_path|.\n"
                + "\t\t\tGame\t\t\t|gameinfo_path|hl2_textures_dir.vpk\n"
                + "\t\t\tGame\t\t\t|gameinfo_path|hl2_misc_dir.vpk\n"
                + "\t\t\tGame\t\t\t|gameinfo_path|hl2_sound_misc_dir.vpk\n"
                + "\t\t\tGame\t\t\t|gameinfo_path|hl2_pak_dir.vpk\n"
                + "\t\t\tGame\t\t\t|gameinfo_path|hl2\n"
                + "\t\t\tGame\t\t\t|all_source_engine_paths|platform/platform_misc_dir.vpk\n"
                + "\t\t\tGame\t\t\tsome_missing_dir.vpk\n"   // must be skipped silently
                + "\t\t\tGame\t\t\thl2/*.vpk\n"                // wildcard: never spliced
                + "\t\t\tGameBin\t\t|gameinfo_path|bin\n"       // not a content key
                + "\t\t}\n\t}\n}\n").getBytes(StandardCharsets.UTF_8));
        // a platform VPK referenced by the all_source_engine_paths token
        Path platform = srceng.resolve("platform");
        Files.createDirectories(platform);
        Files.write(platform.resolve("platform_misc_dir.vpk").toFile().toPath(), new byte[0]);

        // splice collects hl2's four VPKs + platform's one, in hl2's order
        List<com.z.halfsourceg100.Tier2Jni.BaseMount> spliced = TcFirstAid.spliceHl2SearchPaths();
        ok(spliced != null && spliced.size() == 2, "splice: two base dirs found");
        boolean hl2Four = false, platformOne = false;
        if (spliced != null) {
            for (com.z.halfsourceg100.Tier2Jni.BaseMount bm : spliced) {
                if (bm.gameDir.equals("hl2") && bm.vpks.size() == 4) hl2Four = true;
                if (bm.gameDir.equals("platform") && bm.vpks.contains("platform_misc_dir.vpk")) platformOne = true;
            }
        }
        ok(hl2Four, "splice: hl2's four VPKs by exact name");
        ok(platformOne, "splice: platform VPK via all_source_engine_paths token");

        // the TC regen now carries the spliced mounts (NOT the validator's)
        Path tc = srceng.resolve("splicetc");
        Files.createDirectories(tc);
        Files.write(tc.resolve("gameinfo.txt").toFile().toPath(), (
                "\"GameInfo\"\n{\n\tgame \"X\"\n\tFileSystem\n\t{\n"
                + "\t\tSearchPaths\n\t\t{\n"
                + "\t\t\tGame\t\t\t|gameinfo_path|.\n"
                + "\t\t\tGame\t\t\t../hl2\n"
                + "\t\t}\n\t}\n}\n").getBytes(StandardCharsets.UTF_8));
        String summary = TcFirstAid.fixGameinfo(tc.toFile());
        ok(summary != null && summary.contains("hl2 gameinfo splice"),
                "splice: summary names the mount source");
        String out = read(tc.resolve("gameinfo.txt"));
        ok(out.contains("|all_source_engine_paths|hl2/hl2_sound_misc_dir.vpk\n"),
                "splice: the sound VPK mounts by exact name (the v0.6.3 symptom fix)");
        ok(out.contains("|all_source_engine_paths|platform/platform_misc_dir.vpk\n"),
                "splice: platform VPK mounted");
        ok(!out.contains("*.vpk"), "splice: no wildcards ever");
        ok(!out.contains("some_missing_dir"), "splice: missing files skipped");
        ok(!out.contains("GameBin"), "splice: GameBin never spliced");
        ok(read(tc.resolve("cfg/config.cfg")).contains("mat_vsync 1"),
                "splice: config.cfg seeded from hl2");
        // seeded once only — an edited config survives a regen
        Files.write(tc.resolve("cfg/config.cfg").toFile().toPath(),
                "cl_showfps 1\n".getBytes(StandardCharsets.UTF_8));
        ok(!TcFirstAid.seedConfig(tc.toFile()), "splice: seedConfig never overwrites");

        // an hl2/gameinfo.txt that WE generated is not a trusted source
        Files.write(hl2.resolve("gameinfo.txt").toFile().toPath(), (
                "\"gameinfo\"\n{\n\t// generated by half-sourceG100 mount planner (Tier 2, rev 7)\n"
                + "\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n"
                + "\t\t\tGame\t\t|all_source_engine_paths|hl2/hl2_textures_dir.vpk\n"
                + "\t\t}\n\t}\n}\n").getBytes(StandardCharsets.UTF_8));
        ok(TcFirstAid.spliceHl2SearchPaths() == null,
                "splice: our own generated hl2 gameinfo is not trusted");
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
                        new ArrayList<String>(), new ArrayList<>()).getBytes(StandardCharsets.UTF_8));
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

    /**
     * Rev-6 deep validation: real VPK bytes on disk. REAL-format archives
     * (the engine's own writer: part descriptors until the 0xffff
     * PACKFILEINDEX_END terminator — v0.6.3 fix; the v0.6.2 validator
     * demanded terminator 0 and falsely rejected every real Steam VPK on
     * the device) pass; a truncated archive (the v0.6.1 field crash —
     * header claims more tree than the file holds) and a garbage-tree
     * archive are rejected and never reach the generated gameinfo; a
     * multi-part entry walks every part; enumeration mounts only valid
     * _dir.vpks, and the generated gameinfo carries their exact names.
     */
    static void vpkValidator(Path srceng) throws Exception {
        Path hl2 = srceng.resolve("hl2");
        Files.createDirectories(hl2);

        // --- a valid v1 _dir.vpk: one entry materials/test.vmt -----------
        // real layout: crc u32, metasize u16, part{ idx u16, ofs u32, len
        // u32 }, terminator u16 == 0xffff (PACKFILEINDEX_END), preload
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        zstr(t, "vmt"); zstr(t, "materials"); zstr(t, "test");
        le32(t, 42);    // crc
        le16(t, 0);     // preload bytes (metasize)
        le16(t, 0);     // part: archive index
        le32(t, 0);     // part: entry offset
        le32(t, 10);    // part: entry length
        le16(t, 0xFFFF);// terminator: PACKFILEINDEX_END — what real VPKs carry
        zstr(t, ""); zstr(t, ""); zstr(t, "");
        byte[] tree = t.toByteArray();
        ByteArrayOutputStream f1 = new ByteArrayOutputStream();
        le32(f1, 0x55aa1234); le32(f1, 1); le32(f1, tree.length);
        f1.write(tree);
        Files.write(hl2.resolve("hl2_misc_dir.vpk"), f1.toByteArray());
        ok(TcFirstAid.vpkTreeOk(hl2.resolve("hl2_misc_dir.vpk").toFile()),
                "vpk: real-format v1 archive (0xffff terminator) passes deep validation — the v0.6.3 fix");

        // --- a multi-part entry (file spanning chunk archives) ------------
        ByteArrayOutputStream mp = new ByteArrayOutputStream();
        zstr(mp, "vmt"); zstr(mp, "materials"); zstr(mp, "multi");
        le32(mp, 43);       // crc
        le16(mp, 0);        // metasize
        le16(mp, 0);        // part 1: archive index
        le32(mp, 0);        // part 1: offset
        le32(mp, 10);       // part 1: length
        le16(mp, 1);        // part 2: archive index (multi-part!)
        le32(mp, 0);        // part 2: offset
        le32(mp, 8);        // part 2: length
        le16(mp, 0xFFFF);   // terminator after the LAST part
        zstr(mp, ""); zstr(mp, ""); zstr(mp, "");
        byte[] mpTree = mp.toByteArray();
        ByteArrayOutputStream fmp = new ByteArrayOutputStream();
        le32(fmp, 0x55aa1234); le32(fmp, 1); le32(fmp, mpTree.length);
        fmp.write(mpTree);
        Files.write(hl2.resolve("hl2_multi_dir.vpk"), fmp.toByteArray());
        ok(TcFirstAid.vpkTreeOk(hl2.resolve("hl2_multi_dir.vpk").toFile()),
                "vpk: multi-part entry (two parts before 0xffff) walks all parts");

        // --- the old false-rejection: a 0x0000 terminator is NOT the
        //     engine's end marker — the walk must reject this entry
        //     (its parts list never terminates with 0xffff)
        ByteArrayOutputStream t0 = new ByteArrayOutputStream();
        zstr(t0, "vmt"); zstr(t0, "materials"); zstr(t0, "legacy0");
        le32(t0, 44);    // crc
        le16(t0, 0);     // metasize
        le16(t0, 0);     // part: archive index
        le32(t0, 0);     // part: offset
        le32(t0, 10);    // part: length
        le16(t0, 0);     // WRONG terminator (the v0.6.2 test fixture form)
        zstr(t0, ""); zstr(t0, ""); zstr(t0, "");
        byte[] t0Tree = t0.toByteArray();
        ByteArrayOutputStream f0 = new ByteArrayOutputStream();
        le32(f0, 0x55aa1234); le32(f0, 1); le32(f0, t0Tree.length);
        f0.write(t0Tree);
        Files.write(hl2.resolve("hl2_legacy0_dir.vpk"), f0.toByteArray());
        ok(!TcFirstAid.vpkTreeOk(hl2.resolve("hl2_legacy0_dir.vpk").toFile()),
                "vpk: 0x0000-terminated entry rejected (not a real terminator)");

        // --- a truncated archive: header claims a tree the file doesn't hold
        byte[] whole = f1.toByteArray();
        Files.write(hl2.resolve("hl2_pak_dir.vpk"),
                Arrays.copyOf(whole, whole.length - 4));
        ok(!TcFirstAid.vpkTreeOk(hl2.resolve("hl2_pak_dir.vpk").toFile()),
                "vpk: truncated archive rejected (the v0.6.1 FORTIFY crash class)");

        // --- a garbage tree: correct signature/size, non-terminating strings
        byte[] junk = new byte[256];
        new java.util.Random(42).nextBytes(junk);
        junk[0] = 'a'; // ensure the first string is non-empty
        ByteArrayOutputStream f2 = new ByteArrayOutputStream();
        le32(f2, 0x55aa1234); le32(f2, 1); le32(f2, junk.length);
        f2.write(junk);
        Files.write(hl2.resolve("hl2_junk_dir.vpk"), f2.toByteArray());
        ok(!TcFirstAid.vpkTreeOk(hl2.resolve("hl2_junk_dir.vpk").toFile()),
                "vpk: garbage tree rejected");

        // --- a v2 archive: 28-byte header, tree after it ------------------
        ByteArrayOutputStream f3 = new ByteArrayOutputStream();
        le32(f3, 0x55aa1234); le32(f3, 2); le32(f3, tree.length);
        for (int i = 0; i < 16; i++) f3.write(0); // embedded/chunk/self/sig sizes
        f3.write(tree);
        Files.write(hl2.resolve("hl2_v2_dir.vpk"), f3.toByteArray());
        ok(TcFirstAid.vpkTreeOk(hl2.resolve("hl2_v2_dir.vpk").toFile()),
                "vpk: v2 archive (28-byte header) passes");

        // --- enumeration: only valid _dir.vpks, exact names ----------------
        java.util.List<Tier2Jni.BaseMount> mounts = TcFirstAid.enumerateBaseMounts();
        ok(mounts.size() == 1 && "hl2".equals(mounts.get(0).gameDir),
                "vpk: enumeration finds exactly the hl2 group");
        java.util.List<String> names = mounts.get(0).vpks;
        ok(names.size() == 3 && names.contains("hl2_misc_dir.vpk")
                        && names.contains("hl2_multi_dir.vpk")
                        && names.contains("hl2_v2_dir.vpk"),
                "vpk: valid archives enumerated by exact name");
        ok(TcFirstAid.lastRejects.contains("hl2/hl2_pak_dir.vpk")
                        && TcFirstAid.lastRejects.contains("hl2/hl2_junk_dir.vpk")
                        && TcFirstAid.lastRejects.contains("hl2/hl2_legacy0_dir.vpk"),
                "vpk: corrupt and fake-terminator archives named in lastRejects");

        // --- the generated gameinfo mounts exactly the valid ones ----------
        Path tc = srceng.resolve("vpkmod");
        Files.createDirectories(tc);
        Files.write(tc.resolve("gameinfo.txt"), (
                "\"GameInfo\"\n{\n\tgame \"V\"\n}\n").getBytes(StandardCharsets.UTF_8));
        TcFirstAid.fixGameinfo(tc.toFile());
        String gi = read(tc.resolve("gameinfo.txt"));
        ok(gi.contains("|all_source_engine_paths|hl2/hl2_misc_dir.vpk")
                        && gi.contains("|all_source_engine_paths|hl2/hl2_multi_dir.vpk")
                        && gi.contains("|all_source_engine_paths|hl2/hl2_v2_dir.vpk"),
                "vpk: gameinfo mounts the valid archives by exact name");
        ok(!gi.contains("hl2_pak_dir.vpk") && !gi.contains("hl2_junk_dir.vpk")
                        && !gi.contains("hl2_legacy0_dir.vpk"),
                "vpk: gameinfo never mounts the rejected archives");
        ok(!gi.contains("*.vpk"), "vpk: no wildcard mounts anywhere");

        // a truncated VPK inside the MOD folder is skipped, not mounted
        Files.write(tc.resolve("brokenmod_dir.vpk"),
                Arrays.copyOf(whole, whole.length - 4));
        Files.write(tc.resolve("gameinfo.txt"), (
                "\"GameInfo\"\n{\n\tgame \"V2\"\n}\n").getBytes(StandardCharsets.UTF_8));
        TcFirstAid.fixGameinfo(tc.toFile());
        String gi2 = read(tc.resolve("gameinfo.txt"));
        ok(!gi2.contains("brokenmod_dir.vpk"),
                "vpk: truncated mod VPK not mounted");
    }

    /** Little-endian u32 into a stream. */
    static void le32(java.io.ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }

    /** Little-endian u16 into a stream. */
    static void le16(java.io.ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF);
    }

    /** NUL-terminated string into a stream. */
    static void zstr(java.io.ByteArrayOutputStream o, String s) {
        o.write(s.getBytes(StandardCharsets.US_ASCII), 0, s.length());
        o.write(0);
    }

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
