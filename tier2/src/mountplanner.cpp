#include "hsg100/mountplanner.hpp"
#include "hsg100/keyvalues.hpp"

#include <algorithm>
#include <set>
#include <sstream>

namespace hsg100 {

namespace {

// Escape a string for use inside a quoted KeyValues value.
std::string escapeKv(const std::string &s) {
    std::string o;
    o.reserve(s.size());
    for (char c : s) {
        switch (c) {
            case '\\': o += "\\\\"; break;
            case '"':  o += "\\\""; break;
            case '\n': o += "\\n";  break;
            case '\t': o += "\\t";  break;
            default:   o += c;      break;
        }
    }
    return o;
}

// gameinfo.txt SearchPaths line for a VPK file found on disk, or "" when
// the file must NOT be listed.
//
// ENGINE SEMANTICS (verified against the pinned v1.17.0025 source,
// filesystem/basefilesystem.cpp + public/filesystem_init.cpp):
//   - a SearchPaths entry ending in .vpk is opened AS THE EXACT FILENAME
//     given (CBaseFileSystem::AddSearchPathInternal -> AddVPKFile ->
//     CPackedStoreRefCount(name)); there is NO "<name> -> <name>_dir.vpk"
//     synthesis — a stripped name mounts NOTHING, silently (IsEmpty -> drop)
//   - therefore _dir.vpk archives MUST be listed with their full real name
//   - numbered chunk archives (pak01.003.vpk) are opened through their _dir
//     entry by the VPK code itself and are never listed
//   - wildcard entries (*.vpk) glob the real filesystem — powerful but
//     DANGEROUS here: the glob mounts whatever exists, and the engine walks
//     every mounted VPK directory with unchecked strlen/sprintf
//     (CPackedStore::GetFileList). A single corrupt or truncated archive in
//     the folder = SIGABRT (FORTIFY vsprintf abort; the v0.6.1 field
//     crash). Base VPKs are therefore emitted as EXACT VALIDATED names by
//     the launcher (see BaseMount); this helper is only used for the mod's
//     own root VPKs, which the importer/first-aid already signature-check.
std::string vpkMountName(const std::string &file) {
    const std::string ext = ".vpk";
    if (file.size() < ext.size() || file.compare(file.size() - ext.size(), ext.size(), ext) != 0)
        return "";
    std::string stem = file.substr(0, file.size() - ext.size());

    // numbered chunk: <anything>.<exactly 3 digits>
    auto dot = stem.find_last_of('.');
    if (dot != std::string::npos && stem.size() - dot - 1 == 3) {
        bool digits = true;
        for (size_t i = dot + 1; i < stem.size(); ++i)
            if (stem[i] < '0' || stem[i] > '9') { digits = false; break; }
        if (digits) return "";
    }

    // real filename, _dir suffix KEPT — the engine opens exactly this name
    return file;
}

} // namespace

MountPlan planMount(const ModInfo &mi, const std::string &modGameDir, uint32_t baseGameId,
                    const std::vector<BaseMount> &baseMounts) {
    MountPlan plan;
    plan.modGameDir = modGameDir;

    if (modGameDir.empty()) {
        plan.warnings.push_back("mod game dir name is empty");
        return plan;
    }

    // ---- search path emission (rev 6) ---------------------------------------
    // The engine expands exactly two tokens (plus the Steam-only |appid_*|):
    //   |gameinfo_path|            directory containing this gameinfo.txt
    //   |all_source_engine_paths|  base directories (srceng root)
    // History of this emission, because every revision was a field lesson:
    //   rev 2: |<moddir>| / |hl2| / |platform| — literal names, nothing mounted
    //   rev 3: fixed tokens but STRIPPED the "_dir" suffix from VPK names —
    //          the engine opens the EXACT filename given, so every base
    //          content line silently mounted nothing (the Minerva
    //          black-screen: 1936 missing materials, zero VPKs mounted)
    //   rev 4: real filenames + *.vpk wildcard base mounts — content mounted
    //          and the map LOADED, but the wildcard also mounted a
    //          corrupt/truncated VPK, and the engine's VPK directory walk
    //          (sprintf into MAX_PATH, unchecked strlen) aborted the process:
    //          "FORTIFY: vsprintf: prevented 77844-byte write into 260-byte
    //          buffer" in CPackedStore::GetFileList (v0.6.1 field crash)
    //   rev 5: the launcher enumerates the base VPKs that actually exist and
    //          VALIDATES each one (signature + full bounds-checked tree walk,
    //          truncation guard) before handing the list here. We emit exact
    //          filenames only — a corrupt archive is skipped with a warning,
    //          never mounted. Loose dirs stay as the universal fallback: they
    //          mount no VPKs by themselves (AddPackFiles only scans zip*.pak)
    //          but keep loose-file content reachable. BUT the validator
    //          demanded a 0x0000 entry terminator — every real Steam VPK
    //          writes 0xffff (PACKFILEINDEX_END) — so all 16 base archives
    //          were falsely rejected on the device and TC mods again booted
    //          with zero base content ("Can't find background image ...",
    //          the v0.6.2 missing-background breakage)
    //   rev 6: same emission, validator fixed — part-descriptor walk until
    //          the real 0xffff terminator (multi-part entries included);
    //          real VPKs validate, corrupt ones still don't
    std::set<std::string> mounts;
    for (const auto &v : mi.vpks) {
        std::string m = vpkMountName(v);
        if (!m.empty()) mounts.insert(m);
    }
    if (!mounts.empty())
        plan.warnings.push_back("mod VPKs mounted by exact filename: " +
                                std::to_string(mounts.size()) + " archive(s)");
    else
        plan.warnings.push_back("no VPK archives found; loose content mounted from the mod dir");

    std::ostringstream o;
    o << "\"gameinfo\"\n{\n";
    o << "\t// generated by half-sourceG100 mount planner (Tier 2, rev 6)\n";
    o << "\tgame \"" << escapeKv(mi.game.empty() ? modGameDir : mi.game) << "\"\n";
    o << "\ttitle \"" << escapeKv(mi.modName) << "\"\n";
    o << "\ttitle2 \"\"\n";
    o << "\ttype \"singleplayer_only\"\n";
    o << "\tSteamAppId \"" << baseGameId << "\"\n";
    o << "\n\tfilesystem\n\t{\n";
    o << "\t\tSteamAppId\t\t\"" << baseGameId << "\"\n";
    o << "\t\tToolsAppId\t\t\"211\"\n";
    o << "\n\t\tSearchPaths\n\t\t{\n";
    o << "\t\t\tGame\t\t|gameinfo_path|.\n";
    for (const auto &m : mounts)
        o << "\t\t\tGame\t\t|gameinfo_path|" << m << "\n";
    o << "\t\t\tGame\t\thl2\n";
    // exact, validated base VPKs (rev 5) — emitted before the loose dir so
    // packed content wins over loose leftovers in the same folder
    for (const auto &bm : baseMounts) {
        for (const auto &v : bm.vpks)
            o << "\t\t\tGame\t\t|all_source_engine_paths|" << bm.gameDir << "/" << v << "\n";
    }
    // loose base dirs, always (fallback reachability for loose content)
    o << "\t\t\tGame\t\t|all_source_engine_paths|hl2\n";
    o << "\t\t\tGame\t\t|all_source_engine_paths|episodic\n";
    o << "\t\t\tGame\t\t|all_source_engine_paths|ep2\n";
    o << "\t\t\tGame\t\t|all_source_engine_paths|platform\n";
    o << "\t\t}\n\t}\n}\n";

    plan.gameinfoText = o.str();
    plan.warnings.push_back("original gameinfo preserved as gameinfo.pc.txt");

    // warnings that describe the compat contract for this mod class
    switch (mi.modClass) {
        case ModClass::SourceMod:
            plan.warnings.push_back("SOURCE_MOD: mount plan assumes Tier-1 native "
                                    "client/server .so will be built and placed in "
                                    + modGameDir + "/bin/ before this mod is fully playable");
            break;
        case ModClass::DllOnly:
            plan.warnings.push_back("DLL_ONLY: mod code (client.dll/server.dll) will NOT run; "
                                    "base HL2 code is the fallback — expect scripted-entity "
                                    "differences, content (maps/models/materials) fully mounted");
            break;
        case ModClass::ContentOnly:
            plan.warnings.push_back("CONTENT_ONLY: full compatibility expected");
            break;
        case ModClass::StandaloneTc:
            plan.warnings.push_back("STANDALONE_TC: run the TC extractor first, then re-classify");
            break;
        case ModClass::Empty:
        case ModClass::BrokenGameinfo:
            plan.warnings.push_back("nothing to mount: mod is empty or broken");
            return plan;
    }

    plan.ok = true;
    return plan;
}

} // namespace hsg100
