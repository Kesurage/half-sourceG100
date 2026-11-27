// half-sourceG100 — JNI bridge to the tier2 mod-compatibility library.
//
// ONE source of truth: the launcher calls the same tier2 C++ code the engine
// uses (classifyMod + planMount). No Java re-implementation of either.
//
// Data crossings are JSON strings emitted here; all string content is escaped
// to pure ASCII (\uXXXX for non-ASCII) so NewStringUTF is always valid MUTF-8.
// The gameinfo PLAN generation takes discrete parameters so no JSON parsing
// is needed on the C++ side.

#include "hsg100/modclassifier.hpp"
#include "hsg100/mountplanner.hpp"

#include <jni.h>

#include <string>

using namespace hsg100;

namespace {

const char *HEX = "0123456789abcdef";

void appendU16(std::string &out, unsigned v) {
    out += "\\u";
    out += HEX[(v >> 12) & 0xf];
    out += HEX[(v >> 8) & 0xf];
    out += HEX[(v >> 4) & 0xf];
    out += HEX[v & 0xf];
}

// JSON string body (quotes not included), ASCII-only output.
void jsonEscape(const std::string &s, std::string &out) {
    size_t i = 0;
    while (i < s.size()) {
        unsigned char c = (unsigned char)s[i];
        if (c == '"') { out += "\\\""; i++; }
        else if (c == '\\') { out += "\\\\"; i++; }
        else if (c == '\n') { out += "\\n"; i++; }
        else if (c == '\r') { out += "\\r"; i++; }
        else if (c == '\t') { out += "\\t"; i++; }
        else if (c < 0x20) {
            out += "\\u00";
            out += HEX[(c >> 4) & 0xf];
            out += HEX[c & 0xf];
            i++;
        } else if (c < 0x80) {
            out += (char)c;
            i++;
        } else {
            // decode UTF-8 (2-4 bytes) -> \uXXXX (surrogate pair above 0xFFFF)
            unsigned cp = 0;
            int extra = 0;
            if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; extra = 1; }
            else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; extra = 2; }
            else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; extra = 3; }
            else { out += '?'; i++; continue; }

            bool ok = (i + (size_t)extra < s.size());
            if (ok) {
                for (int k = 1; k <= extra; k++) {
                    unsigned char cc = (unsigned char)s[i + k];
                    if ((cc & 0xC0) != 0x80) { ok = false; break; }
                    cp = (cp << 6) | (cc & 0x3F);
                }
            }
            if (!ok) { out += '?'; i++; continue; }
            i += (size_t)extra + 1;
            if (cp > 0xFFFF) {
                unsigned v = cp - 0x10000;
                appendU16(out, 0xD800 + (v >> 10));
                appendU16(out, 0xDC00 + (v & 0x3FF));
            } else {
                appendU16(out, cp);
            }
        }
    }
}

std::string jstringToStd(JNIEnv *env, jstring j) {
    if (!j) return std::string();
    const char *p = env->GetStringUTFChars(j, nullptr);
    std::string out = p ? p : "";
    env->ReleaseStringUTFChars(j, p);
    return out;
}

ModClass classFromString(const std::string &s) {
    if (s == "SOURCE_MOD") return ModClass::SourceMod;
    if (s == "CONTENT_ONLY") return ModClass::ContentOnly;
    if (s == "DLL_ONLY") return ModClass::DllOnly;
    if (s == "STANDALONE_TC") return ModClass::StandaloneTc;
    if (s == "BROKEN_GAMEINFO") return ModClass::BrokenGameinfo;
    return ModClass::Empty;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_z_halfsourceg100_Tier2Jni_classifyDir(JNIEnv *env, jclass, jstring jpath) {
    if (!jpath) return nullptr;
    std::string path = jstringToStd(env, jpath);
    ModInfo mi = classifyMod(path);

    std::string o = "{\"class\":\"";
    o += modClassName(mi.modClass);
    o += "\",\"game\":\"";   jsonEscape(mi.game, o);           o += "\",";
    o += "\"title\":\"";    jsonEscape(mi.modName, o);         o += "\",";
    o += "\"lineage\":\"";  jsonEscape(mi.engineLineage, o);   o += "\",";
    o += "\"steamAppId\":"; o += std::to_string(mi.steamAppId); o += ",";
    o += "\"hasWindowsDlls\":";    o += mi.hasWindowsDlls ? "true" : "false";       o += ",";
    o += "\"hasSourceCode\":";     o += mi.hasSourceCode ? "true" : "false";        o += ",";
    o += "\"hasStandaloneEngine\":"; o += mi.hasStandaloneEngine ? "true" : "false"; o += ",";
    o += "\"vpks\":[";
    for (size_t i = 0; i < mi.vpks.size(); ++i) {
        if (i) o += ",";
        o += "\""; jsonEscape(mi.vpks[i], o); o += "\"";
    }
    o += "],\"notes\":[";
    for (size_t i = 0; i < mi.notes.size(); ++i) {
        if (i) o += ",";
        o += "\""; jsonEscape(mi.notes[i], o); o += "\"";
    }
    o += "]}";
    return env->NewStringUTF(o.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_z_halfsourceg100_Tier2Jni_planGameinfo(JNIEnv *env, jclass,
        jstring jmodClass, jstring jmodDir, jstring jgame, jstring jtitle,
        jint jappId, jobjectArray jvpks) {
    ModInfo mi;
    mi.modClass = classFromString(jstringToStd(env, jmodClass));
    mi.game = jstringToStd(env, jgame);
    mi.modName = jstringToStd(env, jtitle);
    mi.steamAppId = (uint32_t)jappId;

    std::string modDir = jstringToStd(env, jmodDir);
    if (modDir.empty()) modDir = "mod";

    if (jvpks) {
        jsize n = env->GetArrayLength(jvpks);
        mi.vpks.reserve((size_t)(n > 0 ? n : 0));
        for (jsize i = 0; i < n; ++i) {
            jstring s = (jstring)env->GetObjectArrayElement(jvpks, i);
            if (s) {
                mi.vpks.push_back(jstringToStd(env, s));
                env->DeleteLocalRef(s);
            }
        }
    }

    MountPlan plan = planMount(mi, modDir, 220);

    std::string o = "{\"ok\":";
    o += plan.ok ? "true" : "false";
    o += ",\"modDir\":\""; jsonEscape(plan.modGameDir, o); o += "\",";
    o += "\"gameinfoText\":\""; jsonEscape(plan.gameinfoText, o); o += "\",";
    o += "\"warnings\":[";
    for (size_t i = 0; i < plan.warnings.size(); ++i) {
        if (i) o += ",";
        o += "\""; jsonEscape(plan.warnings[i], o); o += "\"";
    }
    o += "]}";
    return env->NewStringUTF(o.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_z_halfsourceg100_Tier2Jni_version(JNIEnv *env, jclass) {
    return env->NewStringUTF("tier2 rev 4 (real VPK names + wildcard base mounts)");
}
