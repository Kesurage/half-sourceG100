#!/usr/bin/env python3
"""half-sourceG100 — Tier 2 TC extractor.

Turns a standalone total-conversion mod release (the kind that ships its own
engine: hl2.exe, bin/*.dll, DirectX redists...) into a clean mountable content
package for the half-sourceG100 engine:

    KEEP    *.vpk, maps/, materials/, models/, sound/, resource/, scripts/,
            particles/, cfg/, media/ (bink), actions/ (some TCs), maps content
    STRIP   *.exe, bin/ (Windows DLLs — code never runs on ARM64), redists,
            installers, launchers, docs (optional)
    FIX     gameinfo.txt — SteamAppId -> 220 (HL2), Windows-only paths dropped,
            base content appended last (fallback that kills texture errors)
    OUTPUT  <out>/<modname>/ with the sanitized content + generated gameinfo
            + manifest.json (what was kept/stripped/reclassified)

The classifier logic mirrors tier2/src/modclassifier.cpp (C++ lib used inside
the engine/launcher); this Python twin exists so conversions can run on a PC
or in CI without a compiler.

Usage:
    python3 tools/tc_extractor.py <srcdir> <outdir> [--dry-run] [--keep-docs]
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import struct
import sys
import time

# Content that survives extraction (Tier 2 contract from
# docs/mod-compatibility-pipeline.md)
KEEP_DIRS = {
    "maps", "materials", "models", "sound", "resource", "scripts",
    "particles", "cfg", "media", "scenes", "expressions", "hl2", "platform",
    "actions", "save",
}
KEEP_EXTS = {".vpk", ".bsp", ".nav", ".ain", ".lst", ".res", ".gameinfo"}

# Windows/PC-only junk that never belongs in an Android content package
STRIP_EXTS = {".exe", ".dll", ".so", ".dylib", ".bat", ".cmd", ".lnk",
              ".msi", ".iso", ".url", ".vdf", ".tmp"}
STRIP_DIRS = {"bin", "directx", "dxredist", "redist", "__installers",
              "__support", "steam", "uninstall", "vc_redist", "dotnet"}
STRIP_NAMES = {"autorun.inf", "desktop.ini", "thumbs.db", "run_blackmesa.bat"}

# engine artifacts a standalone TC ships that identify it (must strip)
ENGINE_ARTIFACTS = {"hl2.exe", "srcds.exe", "steam.inf", "hl2_linux",
                    "srcds_linux", "half-life 2.sh"}


# ---------------------------------------------------------------- classifier

def find_gameinfo(root: str) -> tuple[str | None, str | None]:
    """gameinfo.txt at root or one level down. Returns (path, gamedir)."""
    p = os.path.join(root, "gameinfo.txt")
    if os.path.isfile(p):
        return p, root
    try:
        for n in sorted(os.listdir(root)):
            sub = os.path.join(root, n)
            if os.path.isdir(sub) and os.path.isfile(os.path.join(sub, "gameinfo.txt")):
                return os.path.join(sub, "gameinfo.txt"), sub
    except OSError:
        pass
    return None, None


def parse_keyvalues(text: str) -> dict:
    """Tiny KeyValues parser (gameinfo subset). Returns nested dicts."""
    import re

    def tokenize(s: str):
        i, n = 0, len(s)
        while i < n:
            c = s[i]
            if c in " \t\r\n":
                i += 1
            elif s.startswith("//", i):
                while i < n and s[i] != "\n":
                    i += 1
            elif s.startswith("/*", i):
                j = s.find("*/", i + 2)
                i = n if j < 0 else j + 2
            elif c in "{}":
                yield c
                i += 1
            elif c == '"':
                j = i + 1
                buf = []
                while j < n and s[j] != '"':
                    if s[j] == "\\" and j + 1 < n and s[j + 1] in '"\\':
                        j += 1
                    buf.append(s[j])
                    j += 1
                yield ("str", "".join(buf))
                i = j + 1
            else:
                j = i
                while j < n and s[j] not in ' \t\r\n{}"':
                    j += 1
                yield ("str", s[i:j])
                i = j

    def parse(tokens):
        node = {}
        stack = [node]
        pending_key = None
        for tok in tokens:
            if tok == "{":
                child = {}
                if pending_key is None:
                    pending_key = f"_anon{len(node)}"
                stack[-1][pending_key] = child
                stack.append(child)
                pending_key = None
            elif tok == "}":
                if len(stack) > 1:
                    stack.pop()
            else:
                kind, val = tok
                if pending_key is None:
                    pending_key = val
                else:
                    stack[-1][pending_key] = val
                    pending_key = None
        return node

    return parse(tokenize(text))


def classify(root: str) -> dict:
    """Mirror of tier2 modclassifier: classify a mod directory."""
    gi_path, game_dir = find_gameinfo(root)
    info = {
        "gameinfo": gi_path,
        "game": "",
        "title": "",
        "steam_appid": 0,
        "has_windows_dlls": False,
        "has_source": False,
        "standalone_tc": False,
        "class": "EMPTY",
        "notes": [],
    }
    if gi_path is None:
        info["notes"].append("no gameinfo.txt found")
        game_dir = root
    else:
        try:
            with open(gi_path, encoding="utf-8", errors="replace") as f:
                kv = parse_keyvalues(f.read())
            gameinfo = kv.get("GameInfo", kv)
            info["game"] = str(gameinfo.get("game", ""))
            info["title"] = str(gameinfo.get("title", ""))
            try:
                info["steam_appid"] = int(str(gameinfo.get("SteamAppId", "0")).strip())
            except ValueError:
                pass
        except OSError as e:
            info["class"] = "BROKEN_GAMEINFO"
            info["notes"].append(f"unreadable gameinfo: {e}")
            return info

    game_dir = game_dir or root

    bin_dir = os.path.join(game_dir, "bin")
    if os.path.isdir(bin_dir):
        for n in os.listdir(bin_dir):
            if n.lower() in ("client.dll", "server.dll",
                             "client_episodic.dll", "server_episodic.dll"):
                info["has_windows_dlls"] = True

    src_dir = os.path.join(game_dir, "src")
    if os.path.isdir(src_dir):
        for dirpath, _dirnames, filenames in os.walk(src_dir):
            if any(f.endswith((".vcxproj", ".sln", ".cpp", ".mak")) for f in filenames):
                info["has_source"] = True
                break

    for n in os.listdir(game_dir):
        if n.lower() in ENGINE_ARTIFACTS:
            info["standalone_tc"] = True
            break

    has_content = any(
        os.path.isdir(os.path.join(game_dir, d)) for d in KEEP_DIRS
    ) or any(f.lower().endswith(".vpk") for f in os.listdir(game_dir))

    if info["standalone_tc"]:
        info["class"] = "STANDALONE_TC"
    elif info["has_source"]:
        info["class"] = "SOURCE_MOD"
    elif info["has_windows_dlls"]:
        info["class"] = "DLL_ONLY"
    elif has_content:
        info["class"] = "CONTENT_ONLY"
    elif gi_path is None:
        info["class"] = "EMPTY"
    else:
        info["class"] = "BROKEN_GAMEINFO"
    return info


# ---------------------------------------------------------------- vpk check

def vpk_signature(path: str) -> tuple[bool, int]:
    """(valid, version) — quick sanity check on a _dir.vpk."""
    try:
        with open(path, "rb") as f:
            hdr = f.read(8)
        if len(hdr) < 8:
            return False, 0
        sig, ver = struct.unpack("<II", hdr)
        return sig == 0x55AA1234 and ver in (1, 2), ver
    except OSError:
        return False, 0


# ------------------------------------------------------------- gameinfo gen

def vpk_mount_name(file: str) -> str | None:
    """gameinfo mount name for a VPK (Python twin of tier2 mountplanner).

    - _dir suffix is stripped (the engine opens <name>_dir.vpk itself)
    - numbered chunk archives (pak01.003.vpk) load via their _dir entry: None
    """
    if not file.lower().endswith(".vpk"):
        return None
    stem = file[:-4]
    head, _, tail = stem.rpartition(".")
    if head and tail.isdigit() and len(tail) == 3:
        return None  # numbered chunk (loads through its _dir entry)
    # rev 4: real filename, _dir suffix KEPT — the engine opens the exact
    # name given (AddVPKFile -> CPackedStoreRefCount(name)); a stripped name
    # mounts nothing, silently
    return file


def generate_gameinfo(mod_dir_name: str, title: str, appid: int = 220,
                      vpks: tuple[str, ...] | list[str] = ()) -> str:
    """Mount planner output (Python twin of tier2/src/mountplanner.cpp, rev 4).

    Engine-valid tokens only: |gameinfo_path| and |all_source_engine_paths|.
    The mod's own VPKs are listed by REAL filename (_dir kept — the engine
    opens the exact name given; a stripped name mounts nothing). Base content
    rides the engine-native *.vpk wildcard globs, which glob the real
    filesystem and auto-dedupe chunk archives, so whatever VPK layout
    hl2/episodic/ep2/platform actually have gets mounted; missing folders
    contribute nothing. Earlier rows have priority on the 2013 branch.
    """
    safe_title = title or mod_dir_name
    mounts = sorted({m for m in (vpk_mount_name(v) for v in vpks) if m})
    vpk_lines = "".join(f"\t\t\tGame\t\t|gameinfo_path|{m}\n" for m in mounts)
    return f"""// generated by half-sourceG100 tc_extractor (Tier 2, rev 4)
"gameinfo"
{{
\tgame\t"{safe_title}"
\ttitle\t"{safe_title}"
\ttitle2\t""
\ttype\t"singleplayer_only"
\tSteamAppId\t"{appid}"

\tfilesystem
\t{{
\t\tSteamAppId\t\t"{appid}"
\t\tToolsAppId\t\t"211"

\t\tSearchPaths
\t\t{{
\t\t\tGame\t\t|gameinfo_path|.
{vpk_lines}\t\t\tGame\t\thl2
\t\t\tGame\t\t|all_source_engine_paths|hl2/*.vpk
\t\t\tGame\t\t|all_source_engine_paths|hl2
\t\t\tGame\t\t|all_source_engine_paths|episodic/*.vpk
\t\t\tGame\t\t|all_source_engine_paths|episodic
\t\t\tGame\t\t|all_source_engine_paths|ep2/*.vpk
\t\t\tGame\t\t|all_source_engine_paths|ep2
\t\t\tGame\t\t|all_source_engine_paths|platform/*.vpk
\t\t\tGame\t\t|all_source_engine_paths|platform
\t\t}}
\t}}
}}
"""


# ------------------------------------------------------------- mount model

def decide_mount_mode(info: dict) -> str:
    """Dual-root mount model (docs/mount-architecture.md):

    tc    — mods that replace the game config: own gameinfo, optionally own
            code (Tier 1). Mounted at srceng/<tc>/ and launched via -game.
    addon — regular mods that only layer content over HL2. Mounted at
            srceng/hl2/custom/<addon>/, engine auto-scans them; base HL2
            client/server code always runs.
    none  — empty/broken: nothing to mount.
    """
    if info["class"] in ("STANDALONE_TC", "SOURCE_MOD"):
        return "tc"
    if info["class"] in ("CONTENT_ONLY", "DLL_ONLY"):
        return "addon"
    return "none"


# ---------------------------------------------------------------- extractor

def extract(src: str, out: str, dry_run: bool = False, keep_docs: bool = False) -> dict:
    info = classify(src)
    game_dir = os.path.dirname(info["gameinfo"]) if info["gameinfo"] else src
    # the mod's mounted name is its GAME dir (where gameinfo.txt lives), not
    # whatever wrapper folder the release shipped in
    mod_name = os.path.basename(os.path.normpath(game_dir)) or "mod"
    mount_mode = decide_mount_mode(info)
    # dual-root destination: TCs get their own gamedir, addons go under
    # hl2/custom/ (engine auto-scan; see mount-architecture.md)
    dest = (
        os.path.join(out, mod_name)
        if mount_mode == "tc"
        else os.path.join(out, "hl2", "custom", mod_name)
    )
    report = {
        "mod": mod_name,
        "class": info["class"],
        "mount_mode": mount_mode,
        "mount_dest": dest.replace("\\", "/"),
        "title": info["title"] or info["game"],
        "game_dir": game_dir,
        "dest": dest,
        "kept": [],
        "stripped": [],
        "vpks": [],
        "warnings": list(info["notes"]),
        "dry_run": dry_run,
        "gameinfo_generated": False,
    }

    if info["class"] in ("EMPTY", "BROKEN_GAMEINFO"):
        report["warnings"].append("nothing to extract: mod is empty or broken")
        return report

    def keep(rel: str, srcpath: str) -> None:
        report["kept"].append(rel)
        if not dry_run:
            dstpath = os.path.join(dest, rel)
            os.makedirs(os.path.dirname(dstpath), exist_ok=True)
            if os.path.isdir(srcpath):
                shutil.copytree(srcpath, dstpath, dirs_exist_ok=True)
            else:
                shutil.copy2(srcpath, dstpath)

    def strip(rel: str) -> None:
        report["stripped"].append(rel)

    if not dry_run:
        os.makedirs(dest, exist_ok=True)

    # walk one game dir deep + top-level files
    for entry in sorted(os.listdir(game_dir)):
        full = os.path.join(game_dir, entry)
        rel = entry
        low = entry.lower()

        if os.path.isdir(full):
            if low in KEEP_DIRS and low not in STRIP_DIRS:
                if low == "bin":
                    strip(rel)  # bin/ is code, never content
                else:
                    keep(rel, full)
            elif low in STRIP_DIRS:
                strip(rel)
            else:
                # unknown dir: keep only known content subdirs inside it
                kept_any = False
                for sub in sorted(os.listdir(full)):
                    subfull = os.path.join(full, sub)
                    if os.path.isdir(subfull) and sub.lower() in KEEP_DIRS:
                        keep(f"{rel}/{sub}", subfull)
                        kept_any = True
                    elif sub.lower().endswith(".vpk"):
                        keep(f"{rel}/{sub}", subfull)
                        kept_any = True
                if not kept_any:
                    strip(rel)
        else:
            ext = os.path.splitext(low)[1]
            name = low
            if ext in (".vpk",):
                ok, ver = vpk_signature(full)
                if not ok and not full.lower().endswith("_dir.vpk"):
                    # numbered archive chunks have the same header; only _dir
                    # and single-file vpks are validated here — keep chunks
                    keep(rel, full)
                elif ok:
                    report["vpks"].append({"file": entry, "version": ver})
                    keep(rel, full)
                else:
                    strip(rel)
                    report["warnings"].append(f"invalid VPK signature: {entry}")
            elif ext in KEEP_EXTS or name in ("gameinfo.txt",):
                keep(rel, full)
            elif ext in STRIP_EXTS or name in STRIP_NAMES:
                strip(rel)
            elif ext in (".txt", ".md", ".pdf", ".html", ".htm") and not keep_docs:
                strip(rel) if name not in ("gameinfo.txt",) else keep(rel, full)
            elif keep_docs and ext in (".txt", ".md", ".pdf", ".html", ".htm"):
                keep(rel, full)
            else:
                strip(rel)

    # preserve original gameinfo always; generate a new one ONLY for tc mode —
    # addons are pure content under hl2/custom/ (Valve custom-folder semantics:
    # no gameinfo, the engine scan mounts them)
    if info["gameinfo"] and not dry_run:
        shutil.copy2(info["gameinfo"], os.path.join(dest, "gameinfo.txt.orig"))
    if mount_mode == "tc":
        if not dry_run:
            # list the VPKs that actually landed in the package so the
            # generated gameinfo references real archives
            vpks = [f for f in os.listdir(dest) if f.lower().endswith(".vpk")]
            gi = generate_gameinfo(mod_name, report["title"], vpks=vpks)
            with open(os.path.join(dest, "gameinfo.txt"), "w", encoding="utf-8") as f:
                f.write(gi)
        report["gameinfo_generated"] = True
    elif mount_mode == "addon":
        # an addon must not carry an active gameinfo (engine custom-scan mounts
        # it; a stray gameinfo would confuse gamedir detection) — keep only
        # the .orig reference copy
        if not dry_run and info["gameinfo"]:
            stray = os.path.join(dest, "gameinfo.txt")
            if os.path.exists(stray):
                os.remove(stray)
        report["warnings"].append(
            "addon mode: no gameinfo generated; mounted via hl2/custom/ engine scan"
        )

    # class-specific guidance (the honest error-reporting contract)
    if info["class"] == "DLL_ONLY":
        report["warnings"].append(
            "DLL_ONLY: mod code (client.dll/server.dll) will NOT run on ARM64; "
            "content is fully mounted and base HL2 code is the fallback"
        )
    elif info["class"] == "SOURCE_MOD":
        report["warnings"].append(
            "SOURCE_MOD: source tree present — Tier 1 native build recommended; "
            "extracted content will run with base code until then"
        )
    elif info["class"] == "STANDALONE_TC":
        report["warnings"].append(
            "STANDALONE_TC: engine binaries stripped; content extraction applied"
        )

    # manifest
    if not dry_run:
        report["generated_at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
        with open(os.path.join(dest, "manifest.json"), "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
    return report


def main() -> int:
    ap = argparse.ArgumentParser(description="half-sourceG100 Tier 2 TC extractor")
    ap.add_argument("src", help="standalone TC / mod directory")
    ap.add_argument("out", help="output directory (mod content packages land inside)")
    ap.add_argument("--dry-run", action="store_true", help="report only, write nothing")
    ap.add_argument("--keep-docs", action="store_true", help="keep readme/pdf/html files")
    args = ap.parse_args()

    if not os.path.isdir(args.src):
        print(f"error: {args.src} is not a directory", file=sys.stderr)
        return 2

    report = extract(args.src, args.out, dry_run=args.dry_run, keep_docs=args.keep_docs)

    print(f"mod        : {report['mod']}")
    print(f"class      : {report['class']}")
    print(f"mount mode : {report['mount_mode']}  ({report['mount_dest']})")
    print(f"title      : {report['title'] or '-'}")
    print(f"kept       : {len(report['kept'])} item(s)")
    print(f"stripped   : {len(report['stripped'])} item(s)")
    print(f"vpks       : {len(report['vpks'])} validated")
    print(f"gameinfo   : {'generated' if report['gameinfo_generated'] else 'n/a'}")
    for w in report["warnings"]:
        print(f"warning    : {w}")
    if not report["gameinfo_generated"] and report["class"] not in ("EMPTY", "BROKEN_GAMEINFO"):
        print("note       : no gameinfo found; re-classify after adding one", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
