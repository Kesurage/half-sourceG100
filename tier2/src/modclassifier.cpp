#include "hsg100/modclassifier.hpp"
#include "hsg100/keyvalues.hpp"

#include <algorithm>
#include <dirent.h>
#include <fstream>
#include <sys/stat.h>

namespace hsg100 {

const char *modClassName(ModClass c) {
    switch (c) {
        case ModClass::Empty: return "EMPTY";
        case ModClass::BrokenGameinfo: return "BROKEN_GAMEINFO";
        case ModClass::SourceMod: return "SOURCE_MOD";
        case ModClass::ContentOnly: return "CONTENT_ONLY";
        case ModClass::DllOnly: return "DLL_ONLY";
        case ModClass::StandaloneTc: return "STANDALONE_TC";
    }
    return "?";
}

namespace {

bool isDir(const std::string &p) {
    struct stat st;
    return stat(p.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}
bool isFile(const std::string &p) {
    struct stat st;
    return stat(p.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

std::vector<std::string> listDir(const std::string &dir) {
    std::vector<std::string> out;
    DIR *d = opendir(dir.c_str());
    if (!d) return out;
    while (dirent *e = readdir(d)) {
        std::string n = e->d_name;
        if (n == "." || n == "..") continue;
        out.push_back(n);
    }
    closedir(d);
    std::sort(out.begin(), out.end());
    return out;
}

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return std::tolower(c); });
    return s;
}

bool endsWith(const std::string &s, const std::string &suffix) {
    return s.size() >= suffix.size() && s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

std::string readFile(const std::string &p) {
    std::ifstream f(p, std::ios::binary);
    if (!f) return {};
    return std::string(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
}

std::string baseName(std::string p) {
    auto i = p.find_last_of("/\\");
    return i == std::string::npos ? p : p.substr(i + 1);
}

bool dirHasExtension(const std::string &dir, const std::string &ext, int maxDepth = 2) {
    // bounded BFS so a huge tree doesn't cost us
    std::vector<std::pair<std::string, int>> queue{{dir, 0}};
    size_t budget = 4000;
    while (!queue.empty() && budget-- > 0) {
        auto [d, depth] = queue.back();
        queue.pop_back();
        for (const auto &n : listDir(d)) {
            std::string full = d + "/" + n;
            if (endsWith(lower(n), ext)) return true;
            if (depth < maxDepth && isDir(full)) queue.push_back({full, depth + 1});
        }
    }
    return false;
}

bool looksLikeEngineArtifact(const std::string &dir) {
    // a TC that ships its own engine: hl2.exe / srcds.exe / steam.inf / SourceEngine .dll set
    for (const auto &n : listDir(dir)) {
        std::string ln = lower(n);
        if (ln == "hl2.exe" || ln == "srcds.exe" || ln == "steam.inf") return true;
    }
    std::string bin = dir + "/bin";
    if (isDir(bin)) {
        for (const auto &n : listDir(bin)) {
            std::string ln = lower(n);
            // engine dlls a mod would never ship itself unless it brought the engine
            if (ln == "engine.dll" || ln == "vphysics.dll" || ln == "shaderapidx9.dll" || ln == "datacache.dll")
                return true;
        }
    }
    return false;
}

// find gameinfo.txt: root, or one level down (mod installers commonly nest)
std::string findGameinfo(const std::string &root, std::string *gameDirOut) {
    if (isFile(root + "/gameinfo.txt")) {
        if (gameDirOut) *gameDirOut = root;
        return root + "/gameinfo.txt";
    }
    for (const auto &n : listDir(root)) {
        std::string sub = root + "/" + n;
        if (isDir(sub) && isFile(sub + "/gameinfo.txt")) {
            if (gameDirOut) *gameDirOut = sub;
            return sub + "/gameinfo.txt";
        }
    }
    return {};
}

} // namespace

ModInfo classifyMod(const std::string &rootDir) {
    ModInfo mi;
    mi.gamePath = baseName(rootDir);

    if (!isDir(rootDir)) {
        mi.notes.push_back("not a directory: " + rootDir);
        return mi;
    }

    // ---- gameinfo.txt ------------------------------------------------------
    std::string gameDir;
    std::string giPath = findGameinfo(rootDir, &gameDir);
    if (giPath.empty()) {
        mi.notes.push_back("no gameinfo.txt found (root or one level below)");
        // fall through to content sniffing; class set below
    } else {
        std::string err;
        auto root = KeyValue::parse(readFile(giPath), &err);
        if (!root) {
            mi.modClass = ModClass::BrokenGameinfo;
            mi.notes.push_back("gameinfo.txt parse error: " + err);
            return mi;
        }
        // real root is the single top-level node, usually "GameInfo"
        const KeyValue *gameInfo = root->children().size() == 1 ? &root->children()[0] : root.get();
        if (const KeyValue *g = gameInfo->find("game")) mi.game = g->value();
        if (const KeyValue *t = gameInfo->find("title")) mi.modName = t->value();
        if (const KeyValue *g = gameInfo->find("title2")) {
            if (mi.modName.empty()) mi.modName = g->value();
            else mi.modName += " " + g->value();
        }
        if (const KeyValue *a = gameInfo->find("SteamAppId")) {
            try { mi.steamAppId = std::stoul(a->value()); } catch (...) {}
        }
        mi.notes.push_back("gameinfo: " + giPath);
    }

    if (mi.modName.empty()) mi.modName = mi.gamePath;

    // ---- filesystem artifacts ---------------------------------------------
    // VPKs: root level and one level down (both plain _dir.vpk and numbered archives)
    for (const auto &n : listDir(gameDir.empty() ? rootDir : gameDir)) {
        if (endsWith(lower(n), ".vpk"))
            mi.vpks.push_back(n);
    }

    std::string binDir = gameDir.empty() ? rootDir + "/bin" : gameDir + "/bin";
    if (isDir(binDir)) {
        for (const auto &n : listDir(binDir)) {
            std::string ln = lower(n);
            if (ln == "client.dll" || ln == "server.dll" || ln == "client_episodic.dll" || ln == "server_episodic.dll")
                mi.hasWindowsDlls = true;
        }
    }

    // source tree: src/ with project files
    std::string srcDir = gameDir.empty() ? rootDir + "/src" : gameDir + "/src";
    if (isDir(srcDir) && (dirHasExtension(srcDir, ".vcxproj") || dirHasExtension(srcDir, ".sln") ||
                           dirHasExtension(srcDir, ".cpp") || dirHasExtension(srcDir, ".mak")))
        mi.hasSourceCode = true;

    mi.hasStandaloneEngine = looksLikeEngineArtifact(gameDir.empty() ? rootDir : gameDir);

    // content dirs present?
    auto hasContentDir = [&](const char *d) {
        return isDir((gameDir.empty() ? rootDir : gameDir) + "/" + d);
    };
    bool hasContent = hasContentDir("maps") || hasContentDir("materials") || hasContentDir("models") ||
                      hasContentDir("sound") || hasContentDir("resource") || hasContentDir("scripts") ||
                      !mi.vpks.empty();

    // lineage hints from gameinfo text (crude but effective)
    {
        std::string txt = lower(readFile(giPath));
        if (txt.find("mapbase") != std::string::npos) mi.engineLineage = "mapbase";
        else if (mi.hasSourceCode) mi.engineLineage = "sdk2013";
        else mi.engineLineage = "unknown";
    }

    // ---- decision (priority order per pipeline spec) ------------------------
    if (mi.hasStandaloneEngine) {
        mi.modClass = ModClass::StandaloneTc;
        mi.notes.push_back("ships its own engine build: run TC extractor, then re-classify");
    } else if (mi.hasSourceCode) {
        mi.modClass = ModClass::SourceMod;
        mi.notes.push_back("source tree found: Tier 1 native build candidate");
    } else if (mi.hasWindowsDlls) {
        mi.modClass = ModClass::DllOnly;
        mi.notes.push_back("closed Windows DLLs: content mounts (Tier 2), code needs Tier 3");
    } else if (hasContent) {
        mi.modClass = ModClass::ContentOnly;
        mi.notes.push_back("content-only mod: mounts directly (Tier 2)");
    } else if (giPath.empty()) {
        mi.modClass = ModClass::Empty;
        mi.notes.push_back("no gameinfo, no content dirs, no VPKs");
    } else {
        mi.modClass = ModClass::BrokenGameinfo;
        mi.notes.push_back("gameinfo parses but no usable content found");
    }

    return mi;
}

} // namespace hsg100
