# Launcher guide (Phase 3)

The launcher APK is the front half of the one-engine architecture: it manages
`/sdcard/srceng`, classifies every mod with the same tier2 library the engine
uses, converts PC mod zips on-device, and starts the game (our engine bundle
when it ships, or the nillerusr engine app which reads the same root).

## Install

1. Grab `HalfSourceLauncher-<version>.apk` from
   [releases](https://github.com/Kesurage/half-sourceG100/releases) and install
   it (allow "install unknown apps" for your browser/file manager).
2. Open the app once and **grant All files access** when prompted (the content
   root is shared storage — required on Android 11+; the Hot 50 4G runs
   Android 14).

## Content layout (the contract)

```
/sdcard/srceng/
├── hl2/                base HL2 content (from your own Steam copy)
│   └── custom/         regular mods (content layers; engine auto-scan)
├── platform/
└── <tcname>/           TC mods (own gameinfo, launched via -game)
```

- **TC mods** sit at the `srceng/` root. Selecting one in the catalog marks it
  as the launch target (`-game <tcname>`).
- **Regular mods** sit in `hl2/custom/`. Tapping one moves it to
  `hl2/custom_disabled/` (a sibling directory, invisible to the engine scan) —
  that's the off switch.

## The catalog

Two sections (tabs): **TC MODS** and **REGULAR MODS**. Every entry shows its
tier2 class badge:

| Badge | Meaning |
|---|---|
| `CONTENT_ONLY` (green) | pure content — maps/models/materials/sound mount directly, full compatibility expected |
| `SOURCE_MOD` (green) | has source code — Tier-1 native build candidate; content works meanwhile |
| `DLL_ONLY` (orange) | closed Windows DLLs — content mounts, custom code falls back to base HL2 (Tier 3 later) |
| `STANDALONE_TC` (orange) | shipped its own engine — run Import to extract the content |

Long-press any entry for the honest report: what works, what doesn't, and the
recommended action. No silent fallbacks.

## Import (on-device Tier-2 conversion)

Tap **Import** and pick a PC mod **zip** (ModDB downloads, workshop rips, mod
release archives). The importer:

1. Classifies the zip from its entry list (no extraction pass wasted).
2. Decides the mount mode: TC (own `srceng/<name>/` gamedir) vs regular
   (`hl2/custom/<name>/`).
3. Extracts with the KEEP/STRIP contract (`maps materials models sound
   resource scripts particles cfg media scenes expressions actions save` +
   VPK/BSP kept; `bin/`, `*.exe`, `*.dll`, redists stripped; VPK signatures
   validated).
4. For TCs, generates `gameinfo.txt` with the **C++ mount planner via JNI**
   (engine-valid tokens only — see `docs/mount-architecture.md`) and preserves
   the original as `gameinfo.txt.orig`.
5. Writes a `manifest.json` so the conversion is reproducible.

## Launch

- **Engine bundle bundled** (later releases): starts the engine surface
  directly with the selected game.
- **nillerusr engine installed** (`com.valvesoftware.source`): the launcher
  opens it; it reads the same `/sdcard/srceng` root — pick the game there.
- **Neither**: the launcher explains the state and points to releases.

## Diagnostics (Device screen)

Reports the facts the renderer plan depends on: panel resolution (native
resolution policy), OpenGL ES version, Vulkan feature level, storage state,
scan summary, tier2 version. This is the sheet to attach to bug reports.

## Scope notes

- The zip fast-path classifier mirrors the tier2 decision tree over entry
  names; it must stay in sync with `tier2/src/modclassifier.cpp` and
  `tools/tc_extractor.py` (the C++ classifier is the truth for on-disk mods —
  the catalog always uses it).
- The gameinfo PLAN always comes from the C++ planner (`planMount`) via JNI —
  there is exactly one planner, not three.
- Black Mesa and HL:Alyx remain out of scope; see `docs/research-notes.md`.
