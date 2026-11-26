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
}

// ----------------------------------------------------------- mountplanner --
static void testMountPlanner() {
    ModInfo mi;
    mi.modClass = ModClass::DllOnly;
    mi.game = "Closed Mod";
    mi.modName = "Closed Mod";
    MountPlan plan = planMount(mi, "closedmod", 220);
    CHECK(plan.ok);
    // mod content mounted before base, base present as fallback
    auto modPos = plan.gameinfoText.find("|closedmod|");
    auto hl2Pos = plan.gameinfoText.find("|hl2|");
    auto vpkPos = plan.gameinfoText.find("_dir.vpk");
    CHECK(modPos != std::string::npos);
    CHECK(hl2Pos != std::string::npos);
    CHECK(vpkPos != std::string::npos);
    CHECK(modPos < hl2Pos); // priority order
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
