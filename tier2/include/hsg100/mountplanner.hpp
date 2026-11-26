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

// baseGameId: Steam app id our engine build runs as (220 = HL2).
// srcengRoot: absolute path of the srceng/ tree on target (used in |all| paths
//             only when needed; we prefer relative game paths like "hl2").
MountPlan planMount(const ModInfo &mi, const std::string &modGameDir,
                    uint32_t baseGameId = 220);

} // namespace hsg100
