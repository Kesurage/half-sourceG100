// half-sourceG100 — Tier 2 mod compatibility library
// Mod classifier: decides, from a mod's folder on disk, which compat tier
// applies (per docs/mod-compatibility-pipeline.md):
//
//   SOURCE_MOD    source available (src/ dir, or known-upstream SDK2013/Mapbase)
//   CONTENT_ONLY  VPK/maps/materials/... with no Windows-only code
//   DLL_ONLY      closed Windows client/server DLLs, no source
//   STANDALONE_TC ships its own engine build (hl2.exe + its own bin/)
//   EMPTY/BROKEN  nothing usable

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace hsg100 {

enum class ModClass {
    Empty,
    BrokenGameinfo,
    SourceMod,   // Tier 1 candidate
    ContentOnly, // Tier 2, mounts directly
    DllOnly,     // Tier 2 content + Tier 3, or honest error
    StandaloneTc // run TC extractor, then re-classify
};

const char *modClassName(ModClass c);

struct ModInfo {
    ModClass modClass = ModClass::Empty;
    std::string game;              // from gameinfo.txt "game" (title)
    std::string modName;           // from gameinfo.txt "title" or folder name
    std::string gamePath;          // folder name on disk (e.g. "entropyzero2")
    uint32_t steamAppId = 0;       // from gameinfo.txt (220 = HL2)
    std::string engineLineage;     // "mapbase", "sdk2013", "unknown"
    std::vector<std::string> vpks; // discoverable VPK files (basename)
    bool hasWindowsDlls = false;   // bin/client.dll, bin/server.dll present
    bool hasSourceCode = false;    // src/ with .cpp/.vcxproj present
    bool hasStandaloneEngine = false; // own engine dlls/exe (hl2.exe, bin/engine.dll, vphysics.dll)
    std::vector<std::string> notes;   // human-readable findings
};

// Scan a mod directory. gameinfo.txt may be at root or under <root>/<gamename>/.
// Returns ModInfo; never throws.
ModInfo classifyMod(const std::string &rootDir);

} // namespace hsg100
