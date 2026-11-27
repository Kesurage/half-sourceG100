// half-sourceG100 — Tier 2 mod compatibility library
// Mount planner: given a classified ModInfo, produce the rewritten gameinfo.txt
// that makes the mod load under our engine with base-HL2 ARM64 code as
// fallback. This is the "no texture errors" machine: every search path the
// mod's own gameinfo declared gets preserved (content), while Windows-only
// bin paths are dropped and base content is appended last.
//
// Output is plain text ready to write as <moddir>/gameinfo.txt; the original
// is preserved as gameinfo.txt.orig by the caller (tc_extractor).

#pragma once

#include "hsg100/modclassifier.hpp"

#include <string>
#include <vector>

namespace hsg100 {

struct MountPlan {
    std::string modGameDir;              // e.g. "entropyzero2" (folder under srceng/)
    std::string gameinfoText;            // generated gameinfo.txt
    std::vector<std::string> warnings;   // what we changed and why
    bool ok = false;
};

// One base-game content location, as the launcher measured it on device.
struct BaseMount {
    std::string gameDir;          // "hl2", "episodic", "ep2", "platform"
    std::vector<std::string> vpks; // VALIDATED _dir.vpk filenames (exact names,
                                   // tree walked + truncation-checked; chunks
                                   // and corrupt archives excluded)
};

// baseGameId: Steam app id our engine build runs as (220 = HL2).
// baseMounts: base-game VPK inventory measured on device. Rev 5 emits exact
//             validated filenames ONLY — no wildcards (a wildcard mounts
//             whatever lies in the folder, including corrupt/truncated VPKs
//             the engine then walks with unchecked strlen/sprintf: the
//             v0.6.1 FORTIFY abort class). Empty => loose dirs only.
MountPlan planMount(const ModInfo &mi, const std::string &modGameDir,
                    uint32_t baseGameId = 220,
                    const std::vector<BaseMount> &baseMounts = {});

} // namespace hsg100
