// half-sourceG100 — Tier 2 mod compatibility library
// Minimal VPK v1/v2 directory reader: enough to validate an archive and list
// entries. We do NOT extract here (the extractor tool does that); this only
// answers "is this a valid VPK and what's inside" so the mount planner can
// check for engine-critical paths (materials/, models/, maps/ ...).
//
// Format reference: ValvePak / VDC "VPK" — header, then the directory tree
// (extensions -> paths -> filenames), stored little-endian.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace hsg100 {

struct VpkEntry {
    std::string path;      // e.g. "materials/concrete/concretefloor001a.vmt"
    uint32_t crc = 0;
    uint16_t archiveIndex = 0; // 0x7fff = in _dir.vpk itself
    uint32_t entryOffset = 0;
    uint32_t entryLength = 0;
};

struct VpkArchive {
    uint32_t signature = 0;   // 0x55aa1234
    uint32_t version = 0;     // 1 or 2
    std::string path;         // the _dir.vpk file
    std::vector<VpkEntry> entries;
    std::vector<std::string> notes; // non-fatal anomalies
    std::string err;

    bool valid() const { return signature == 0x55aa1234 && err.empty(); }
};

// Read the directory of a *_dir.vpk (or a single-file v1 archive).
VpkArchive readVpkDirectory(const std::string &dirVpkPath);

} // namespace hsg100
