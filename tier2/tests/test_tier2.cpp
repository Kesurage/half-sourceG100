// half-sourceG100 tier2 — tests for keyvalues, modclassifier, vpk, mountplanner
// Plain assert-based tests, no framework. Build & run via tier2/Makefile.

#include "hsg100/keyvalues.hpp"
#include "hsg100/modclassifier.hpp"
#include "hsg100/mountplanner.hpp"
#include "hsg100/vpk.hpp"

#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

using namespace hsg100;

static int failures = 0;
#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            ++failures;                                                      \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);  \
        }                                                                    \
    } while (0)

// ---------------------------------------------------------------- helpers --
static void writeFile(const std::string &p, const std::string &contents) {
    std::ofstream f(p, std::ios::binary);
    f << contents;
}
static void makeDir(const std::string &p) { mkdir(p.c_str(), 0755); }
static std::string tmpDir(const char *tag) {
    std::string d = std::string("/tmp/hsg100-test-") + tag + "-" + std::to_string(getpid());
    std::string cmd = "rm -rf '" + d + "'";
    if (system(cmd.c_str()) != 0) { /* best effort */ }
    makeDir(d);
    return d;
}

// ------------------------------------------------------------- keyvalues --
static void testKeyValuesBasics() {
    auto kv = KeyValue::parse(
        "\"GameInfo\"\n"
        "{\n"
        "\tgame\t\"Entropy : Zero 2\"\n"
        "\ttitle \"EZ2\"\n"
        " // comment \"not a key\"\n"
        " /* block\n"
        "    comment */\n"
        "\tnested\n\t{\n"
        "\t\tinner \"v\"\n"
        "\t}\n"
        "}\n");
    CHECK(kv != nullptr);
    if (!kv) return;
    const KeyValue *gi = kv->find("GameInfo");
    CHECK(gi != nullptr);
    if (!gi) return;
    CHECK(gi->find("game")->value() == "Entropy : Zero 2");
    CHECK(gi->find("title")->value() == "EZ2");
    const KeyValue *n = gi->find("nested");
    CHECK(n != nullptr && n->find("inner")->value() == "v");
    // comment content not parsed as key
    CHECK(gi->find("not") == nullptr);
    CHECK(gi->find("a") == nullptr);
}

static void testKeyValuesEscapesAndErrors() {
    auto kv = KeyValue::parse("\"k\" \"escaped \\\"quote\\\" and \\\\ backslash\"\n");
    CHECK(kv && kv->find("k")->value() == "escaped \"quote\" and \\ backslash");

    std::string err;
    auto bad = KeyValue::parse("\"k\" \"unterminated\n", &err);
    CHECK(bad == nullptr);
    CHECK(!err.empty());

    auto bad2 = KeyValue::parse("\"a\" \"b\" }\n", &err); // stray close
    CHECK(bad2 == nullptr);
}

static void testKeyValuesUnquotedTokens() {
    auto kv = KeyValue::parse("GameInfo { SteamAppId 220 type singleplayer_only }\n");
    CHECK(kv);
    const KeyValue *gi = kv->find("GameInfo");
    CHECK(gi && gi->find("SteamAppId")->value() == "220");
    CHECK(gi && gi->find("type")->value() == "singleplayer_only");
}

// ---------------------------------------------------------- modclassifier --
static std::string makeModDir(const char *tag, const std::string &gameinfo,
                              bool withDlls, bool withContent, bool withSrc,
                              bool withEngineArtifacts) {
    std::string root = tmpDir(tag);
    std::string gd = root + "/mymod";
    makeDir(gd);
    if (!gameinfo.empty()) writeFile(gd + "/gameinfo.txt", gameinfo);
    if (withDlls) {
        makeDir(gd + "/bin");
        writeFile(gd + "/bin/client.dll", "MZ fake");
        writeFile(gd + "/bin/server.dll", "MZ fake");
    }
    if (withContent) {
        makeDir(gd + "/maps");
        writeFile(gd + "/maps/testmap.bsp", "fakebsp");
        makeDir(gd + "/materials");
        writeFile(gd + "/materials/mymat.vmt", "\"vmt\"");
    }
    if (withSrc) {
        makeDir(gd + "/src");
        makeDir(gd + "/src/game");
        writeFile(gd + "/src/game/game.vcxproj", "<Project/>");
        writeFile(gd + "/src/game/sdk_game.cpp", "int main(){}");
    }
    if (withEngineArtifacts) {
        writeFile(gd + "/hl2.exe", "MZ");
        makeDir(gd + "/bin");
        writeFile(gd + "/bin/engine.dll", "MZ");
    }
    return root;
}

static void testClassifierContentOnly() {
    std::string root = makeModDir(
        "contentonly",
        "\"GameInfo\"{ game \"My Map Pack\" title \"MMP\" SteamAppId 220 "
        "filesystem{ SteamAppId 220 SearchPaths{ Game \"My Map Pack\" } } }",
        /*dlls*/ false, /*content*/ true, /*src*/ false, /*engine*/ false);
    ModInfo mi = classifyMod(root);
    CHECK(mi.modClass == ModClass::ContentOnly);
    CHECK(mi.steamAppId == 220);
    CHECK(mi.modName == "MMP");
    CHECK(mi.game == "My Map Pack");
}

static void testClassifierDllOnly() {
    std::string root = makeModDir(
        "dllonly",
        "\"GameInfo\"{ game \"Closed Mod\" SteamAppId 215 }",
        true, true, false, false);
    ModInfo mi = classifyMod(root);
    CHECK(mi.modClass == ModClass::DllOnly);
    CHECK(mi.hasWindowsDlls);
    CHECK(mi.steamAppId == 215);
}

static void testClassifierSourceMod() {
    std::string root = makeModDir(
        "sourcemod",
        "\"GameInfo\"{ game \"EZ2\" title \"Entropy : Zero 2\" filesystem{ "
        "SearchPaths { Game \"|gameinfo_path|.\" } } }",
        false, true, true, false);
    ModInfo mi = classifyMod(root);
    CHECK(mi.modClass == ModClass::SourceMod);
    CHECK(mi.hasSourceCode);
}

static void testClassifierStandaloneTc() {
    std::string root = makeModDir(
        "standalonetc",
        "\"GameInfo\"{ game \"Big TC\" }",
        false, true, false, true);
    ModInfo mi = classifyMod(root);
    CHECK(mi.modClass == ModClass::StandaloneTc);
    CHECK(mi.hasStandaloneEngine);
}

static void testClassifierBrokenAndEmpty() {
    std::string root = tmpDir("broken");
    makeDir(root + "/m");
    writeFile(root + "/m/gameinfo.txt", "\"GameInfo\" { game \"x\" "); // unterminated
    ModInfo mi = classifyMod(root);
    CHECK(mi.modClass == ModClass::BrokenGameinfo);

    std::string root2 = tmpDir("empty");
    makeDir(root2 + "/nothing");
    ModInfo mi2 = classifyMod(root2);
    CHECK(mi2.modClass == ModClass::Empty);
}

// -------------------------------------------------------------------- vpk --
// Build a tiny but structurally valid v1 VPK directory in code and parse it.
static std::string makeTestVpk() {
    // Header v1: sig, version, treeSize (patched after building tree)
    std::string tree;
    auto z = [&tree](const std::string &s) { tree += s; tree += '\0'; };
    auto u16 = [&tree](uint16_t v) { tree += std::string((const char *)&v, 2); };
    auto u32 = [&tree](uint32_t v) { tree += std::string((const char *)&v, 4); };

    // one entry: materials/test.vmt in archive 000, offset 0, length 10, crc 42
    z("vmt");
    z("materials");
    z("test");
    u32(42);      // crc
    u16(0);       // preload bytes
    u16(0);       // archive index (000)
    u32(0);       // entry offset
    u32(10);      // entry length
    u16(0);       // terminator
    z("");        // end filenames
    z("");        // end paths
    z("");        // end extensions

    std::string hdr;
    auto le32 = [](uint32_t v) { return std::string((const char *)&v, 4); };
    hdr += le32(0x55aa1234);
    hdr += le32(1);
    hdr += le32((uint32_t)tree.size());

    std::string path = "/tmp/hsg100-test.vpk";
    writeFile(path, hdr + tree);
    return path;
}

static void testVpkReader() {
    std::string p = makeTestVpk();
    VpkArchive v = readVpkDirectory(p);
    CHECK(v.valid());
    CHECK(v.version == 1);
    CHECK(v.entries.size() == 1);
    if (!v.entries.empty()) {
        CHECK(v.entries[0].path == "materials/test.vmt");
        CHECK(v.entries[0].crc == 42);
        CHECK(v.entries[0].entryLength == 10);
        CHECK(v.entries[0].archiveIndex == 0);
    }

    // invalid signature
    std::string badp = "/tmp/hsg100-test-bad.vpk";
    writeFile(badp, "not a vpk at all");
    VpkArchive bad = readVpkDirectory(badp);
    CHECK(!bad.valid());

    // TRUNCATION (the v0.6.1 field crash): header claims a tree longer than
    // the file. The engine would walk uninitialized heap -> FORTIFY abort;
    // the reader must reject it before any mount.
    std::string full = makeTestVpk();
    std::ifstream inf(full, std::ios::binary);
    std::string whole{std::istreambuf_iterator<char>(inf), {}};
    std::string trunc = whole.substr(0, whole.size() - 4);
    writeFile("/tmp/hsg100-test-trunc.vpk", trunc);
    VpkArchive vt = readVpkDirectory("/tmp/hsg100-test-trunc.vpk");
    CHECK(!vt.valid());
    CHECK(vt.err.find("truncated") != std::string::npos);

    // v2 header layout: 28 bytes total (marker, version, treeSize + four
    // more int32s). Skipping only 24 misparses every v2 tree.
    std::string tree;
    auto z = [&tree](const std::string &s) { tree += s; tree += '\0'; };
    auto u16 = [&tree](uint16_t v) { tree += std::string((const char *)&v, 2); };
    auto u32 = [&tree](uint32_t v) { tree += std::string((const char *)&v, 4); };
    z("txt"); z(" "); z("hello");
    u32(1); u16(0); u16(0x7fff); u32(0); u32(5); u16(0); // embedded in dir
    z(""); z(""); z("");
    std::string hdr2;
    uint32_t marker = 0x55aa1234, ver = 2, tsz = (uint32_t)tree.size();
    hdr2 += std::string((const char *)&marker, 4);
    hdr2 += std::string((const char *)&ver, 4);
    hdr2 += std::string((const char *)&tsz, 4);
    hdr2 += std::string(16, '\0'); // embeddedChunk, chunkHashes, selfHashes, sigSize
    writeFile("/tmp/hsg100-test-v2.vpk", hdr2 + tree);
    VpkArchive v2a = readVpkDirectory("/tmp/hsg100-test-v2.vpk");
    CHECK(v2a.valid());
    CHECK(v2a.version == 2);
    CHECK(v2a.entries.size() == 1);
    if (!v2a.entries.empty())
        CHECK(v2a.entries[0].path == "hello.txt");
}

// ----------------------------------------------------------- mountplanner --
static void testMountPlanner() {
    ModInfo mi;
    mi.modClass = ModClass::DllOnly;
    mi.game = "Closed Mod";
    mi.modName = "Closed Mod";
    mi.vpks = {"closedmod_dir.vpk", "closedmod.003.vpk", "extra.vpk"};
    std::vector<BaseMount> bases;
    bases.push_back({"hl2", {"hl2_misc_dir.vpk", "hl2_pak_dir.vpk"}});
    bases.push_back({"platform", {"platform_misc_dir.vpk"}});
    MountPlan plan = planMount(mi, "closedmod", 220, bases);
    CHECK(plan.ok);
    // engine-valid tokens only. The engine expands |gameinfo_path| and
    // |all_source_engine_paths|; rev 2's |moddir| / |hl2| / |platform| were
    // literal directory names and mounted nothing.
    CHECK(plan.gameinfoText.find("|gameinfo_path|.") != std::string::npos);
    CHECK(plan.gameinfoText.find("|all_source_engine_paths|hl2") != std::string::npos);
    CHECK(plan.gameinfoText.find("|all_source_engine_paths|platform") != std::string::npos);
    CHECK(plan.gameinfoText.find("|closedmod|") == std::string::npos);
    CHECK(plan.gameinfoText.find("|hl2|") == std::string::npos);
    CHECK(plan.gameinfoText.find("|platform|") == std::string::npos);
    // the mod's VPKs are mounted by REAL filename — _dir suffix KEPT
    // (the engine opens the exact name given via AddVPKFile ->
    // CPackedStoreRefCount(name); a stripped name mounts nothing, silently).
    // Numbered chunks are still skipped (they load through their _dir).
    CHECK(plan.gameinfoText.find("|gameinfo_path|closedmod_dir.vpk") != std::string::npos);
    CHECK(plan.gameinfoText.find("|gameinfo_path|extra.vpk") != std::string::npos);
    CHECK(plan.gameinfoText.find("003.vpk") == std::string::npos);
    // rev 5: base VPKs by EXACT validated filename — never a wildcard (a
    // wildcard mounts whatever lies in the folder, including corrupt/
    // truncated archives the engine then walks with unchecked strlen/
    // sprintf: the v0.6.1 "FORTIFY: vsprintf 77844 into 260" abort)
    CHECK(plan.gameinfoText.find("|all_source_engine_paths|hl2/hl2_misc_dir.vpk") != std::string::npos);
    CHECK(plan.gameinfoText.find("|all_source_engine_paths|hl2/hl2_pak_dir.vpk") != std::string::npos);
    CHECK(plan.gameinfoText.find("|all_source_engine_paths|platform/platform_misc_dir.vpk") != std::string::npos);
    CHECK(plan.gameinfoText.find("*.vpk") == std::string::npos);
    // loose base dirs stay mounted as the universal fallback
    CHECK(plan.gameinfoText.find("|all_source_engine_paths|episodic\n") != std::string::npos);
    CHECK(plan.gameinfoText.find("|all_source_engine_paths|ep2\n") != std::string::npos);
    // priority order: mod content above base content (2013 branch: earlier rows win)
    CHECK(plan.gameinfoText.find("|gameinfo_path|.") <
          plan.gameinfoText.find("|all_source_engine_paths|hl2"));
    // our engine runs as HL2
    CHECK(plan.gameinfoText.find("\"220\"") != std::string::npos);
    // it must parse back as KeyValues (round-trip sanity)
    auto kv = KeyValue::parse(plan.gameinfoText);
    CHECK(kv != nullptr);
    if (kv) {
        const KeyValue *gi = kv->find("gameinfo");
        CHECK(gi != nullptr);
        if (gi) CHECK(gi->find("SteamAppId")->value() == "220");
    }

    // empty base inventory => loose dirs only, still a valid plan
    MountPlan noBase = planMount(mi, "closedmod", 220, {});
    CHECK(noBase.ok);
    CHECK(noBase.gameinfoText.find("*.vpk") == std::string::npos);
    CHECK(noBase.gameinfoText.find("|all_source_engine_paths|hl2\n") != std::string::npos);

    // broken mods don't get a plan
    ModInfo broken;
    broken.modClass = ModClass::BrokenGameinfo;
    MountPlan p2 = planMount(broken, "x", 220);
    CHECK(!p2.ok);
}

int main() {
    testKeyValuesBasics();
    testKeyValuesEscapesAndErrors();
    testKeyValuesUnquotedTokens();
    testClassifierContentOnly();
    testClassifierDllOnly();
    testClassifierSourceMod();
    testClassifierStandaloneTc();
    testClassifierBrokenAndEmpty();
    testVpkReader();
    testMountPlanner();

    if (failures == 0) {
        printf("tier2 tests: ALL PASS\n");
        return 0;
    }
    printf("tier2 tests: %d FAILURE(S)\n", failures);
    return 1;
}
