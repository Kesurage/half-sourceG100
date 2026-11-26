#include "hsg100/vpk.hpp"

#include <cstring>
#include <fstream>

namespace hsg100 {

namespace {

struct Reader {
    const std::string &buf;
    size_t pos = 0;
    bool eof = false;

    bool bytes(size_t n) {
        if (eof || pos + n > buf.size()) { eof = true; return false; }
        return true;
    }
    uint32_t u32() {
        if (!bytes(4)) return 0;
        uint32_t v;
        std::memcpy(&v, buf.data() + pos, 4);
        pos += 4;
        return v;
    }
    uint16_t u16() {
        if (!bytes(2)) return 0;
        uint16_t v;
        std::memcpy(&v, buf.data() + pos, 2);
        pos += 2;
        return v;
    }
    // NUL-terminated string
    std::string zstr() {
        size_t start = pos;
        while (pos < buf.size() && buf[pos] != '\0') ++pos;
        std::string s = buf.substr(start, pos - start);
        if (pos < buf.size()) ++pos; // skip NUL
        else eof = true;
        return s;
    }
};

} // namespace

// Entry layout per VDC "VPK Format":
//   CRC u32 | PreloadBytes u16 | ArchiveIndex u16 | EntryOffset u32 |
//   EntryLength u32 | Terminator u16 (0x0000) | preload data (PreloadBytes)
// Directory tree: ext\0 { dir\0 { name\0 <entry> } } with empty string ending
// each level. dir " " (single space) means file is at the root.
VpkArchive readVpkDirectory(const std::string &dirVpkPath) {
    VpkArchive vpk;
    vpk.path = dirVpkPath;

    std::ifstream f(dirVpkPath, std::ios::binary);
    if (!f) {
        vpk.err = "cannot open";
        return vpk;
    }
    std::string buf{std::istreambuf_iterator<char>(f), {}};

    Reader r{buf};
    vpk.signature = r.u32();
    if (vpk.signature != 0x55aa1234) {
        vpk.err = "bad signature (not a VPK)";
        return vpk;
    }
    vpk.version = r.u32();
    if (vpk.version != 1 && vpk.version != 2) {
        vpk.err = "unsupported version " + std::to_string(vpk.version);
        return vpk;
    }
    uint32_t treeSize = r.u32();
    if (vpk.version == 2) {
        // v2 extended header: fileDataSize, archiveSize, selfHashMD5 — skip
        r.u32(); r.u32(); r.u32();
    }
    size_t treeEnd = r.pos + treeSize;

    for (std::string ext = r.zstr(); !r.eof && !ext.empty(); ext = r.zstr()) {
        for (std::string dir = r.zstr(); !r.eof && !dir.empty(); dir = r.zstr()) {
            for (std::string name = r.zstr(); !r.eof && !name.empty(); name = r.zstr()) {
                VpkEntry e;
                e.path = (dir == " ") ? name + "." + ext : dir + "/" + name + "." + ext;
                e.crc = r.u32();
                uint16_t preloadBytes = r.u16();
                e.archiveIndex = r.u16();
                e.entryOffset = r.u32();
                e.entryLength = r.u32();
                uint16_t terminator = r.u16();
                if (r.eof) { vpk.err = "truncated entry: " + e.path; return vpk; }
                if (terminator != 0)
                    vpk.notes.push_back("nonzero terminator on " + e.path);
                if (!r.bytes(preloadBytes)) { vpk.err = "truncated preload: " + e.path; return vpk; }
                r.pos += preloadBytes; // skip preload data
                vpk.entries.push_back(std::move(e));
            }
        }
    }
    if (r.eof) vpk.err = "truncated directory tree";
    else if (r.pos > treeEnd) vpk.err = "directory tree overruns header size";

    return vpk;
}

} // namespace hsg100
