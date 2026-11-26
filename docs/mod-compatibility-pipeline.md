# Mod compatibility pipeline — Tier 1 / 2 / 3 spec

The engine must answer one question for every mod it sees: **what is this, and what can run?** Then it runs the best available path instead of failing.

## Classification

On selecting a mod folder/VPK, inspect:

1. `gameinfo.txt` — `FileSystem` SteamAppId, search paths, mod name.
2. `bin/` contents — Windows-only DLLs (`client.dll`, `server.dll`), or already-built Linux/ARM libraries, or nothing (content-only).
3. `src/` presence or known upstream (mod listed in a registry of source-available mods).

Result:

| Class | Meaning | Path |
|---|---|---|
| `SOURCE_MOD` | Source code available (SDK2013/Mapbase lineage) | Tier 1 |
| `CONTENT_ONLY` | VPK/maps/materials/sound, no or irrelevant binaries | Tier 2 (instant) |
| `DLL_ONLY` | Closed Windows `client.dll`/`server.dll` | Tier 2 content + Tier 3 or "not playable, honest error" |
| `STANDALONE_TC` | Ships its own engine binaries/exe (e.g. many big TCs) | Run content extractor, then re-classify |

## Tier 2 — content extraction (the workhorse)

For a standalone TC release (exe-installer-style distribution):

```
KEEP:   *.vpk, maps/, materials/, models/, sound/, resource/, scripts/, particles/,
        cfg/, bin/Linux or bin/Android if present
STRIP:  *.exe, bin/*.dll, DirectX/ redists, launcher data, autorun, docs (optional)
FIX:    gameinfo.txt → rewrite SteamAppId to HL2 (220), prepend engine search paths,
        remove Windows-only fs paths
MOUNT:  repack KEEP set as VPK (or loose folder mount) + generated gameinfo
```

Rule for DLL-mod content: mount everything except code, then run with **base HL2 ARM64 client/server**. This is what kills "texture errors": 90% of the mod's content (models, materials, maps) works; only custom scripted entities fall back or break, and we can list them.

Output of a conversion is a versioned "compat package" so conversions are reproducible and shareable.

## Tier 1 — native mod builds

- Build environment identical to engine build (NDK r10e + clang 11, waf).
- Mod source (SDK2013/Mapbase) → patch against our fork's diffs → `client.so` + `server.so` → packaged into the mod's `bin/`.
- Maintain a **port registry** (docs table per mod: upstream source URL, patch set, status, fps notes). First entry: Entropy : Zero 2 (`ez2/mapbase`).
- Mapbase mods get one shared "mapbase compat" patch layer; most 2020s mods (EZ2 included) sit on Mapbase, so one layer covers many mods.

## Tier 3 — translation fallback (last resort)

Reuse WinlatorMali-proven components rather than inventing:

- Bionic-runtime Wine build (branch `bionic-mali-1.2` lineage), Box64 for x86→ARM64, D8VK/DXVK where needed.
- `android_sysvshm` for Wine shared memory, audio plugin, BCn→ASTC/ETC2 emulation layer (leegao) — all directly reusable.
- Load only the mod's DLL into the container; engine stays native. ABI glue (custom entity interface between native engine and Wine-hosted client dll) is the hard part — estimated per-mod, not generic.

## Error reporting contract

When a mod fails to fully run, the UI must say:
1. What class the mod is.
2. What part launched (maps ✓, models ✓, custom NPCs ✗ — `npc_breen_monologue` missing code).
3. The recommended action (wait for port / convert only / run in Tier 3 container).

No silent fallbacks, no black-and-missing-texture screens.
