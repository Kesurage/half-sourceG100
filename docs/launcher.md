# Launcher guide (Phase 3)

As of v0.4.0 the launcher and the engine are **ONE APK**: install
`HalfSourceG100-Engine-<version>.apk` and the mod manager starts the game
itself — no separate engine app (details: docs/ENGINE-PACK.md). The
manager-only build (`HalfSourceLauncher-*.apk`) exists for catalog-only use
and tells you to switch to the engine build when you tap LAUNCH.

## Install

1. Grab `HalfSourceG100-Engine-<version>.apk` from
   [releases](https://github.com/Kesurage/half-sourceG100/releases) and install
   it (allow "install unknown apps" for your browser/file manager). Since
   v0.5.0 the engine is 64-bit (arm64-v8a) — the Helio G100 has it; the app
   checks and reports if not.
2. Open the app once and **grant All files access** when prompted (the content
   root is shared storage — required on Android 11+; the Hot 50 4G runs
   Android 14).
3. Copy your Half-Life 2 `hl2` folder (from your own Steam install) to
   `/sdcard/srceng/hl2` — it must contain `gameinfo.txt`.
4. Tap **LAUNCH Half-Life 2**. First launch extracts the engine's touch UI
   and starts the game in immersive landscape at native resolution.

## Content layout (the contract)

```
/sdcard/srceng/
├── hl2/                base HL2 content (from your own Steam copy)
│   └── custom/         regular mods (content layers; engine auto-scan)
├── platform/
└── <tcname>/           TC mods (own gameinfo, launched via -game)
```

- **TC mods** sit at the `srceng/` root. Selecting one in the catalog marks it
  as the launch target (`-game <tcname>`). On LAUNCH the folder is renamed
  argv-safe when needed (the engine's Android command line cannot carry
  spaces — v0.6.0), its gameinfo is regenerated into the engine-valid form
  when it is a PC original (original kept as `gameinfo.pc.txt`), and the
  engine starts. Any folder name works now, including "Minerva Metastasis".
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

## Launch (v0.4.1: no silent deaths)

Every launch now passes a hard preflight gate, in order:

1. **All-files access** granted? If not: a dialog explains that the engine
   reads `/sdcard/srceng` and would die silently after the black screen
   without it — Grant button included.
2. **Content root exists?** `srceng/` with `hl2/` — otherwise instructions.
3. **HL2 base content present?** v0.4.0 counted a bare `maps/` dir as
   "present"; v0.4.1 requires a real mounted VPK set (`*_dir.vpk`) or
   `gameinfo.txt` + `maps/` — the engine cannot boot without them.
4. Only then: prefs written, statics armed, SDLActivity starts.

## Crash reports (v0.4.1)

The engine now always launches with `-condebug`, so it writes its own
`console.log` into the game dir. On top of that, three traps make sure no
failure is ever invisible again (all under `srceng/logs/`, viewable and
shareable in Diagnostics):

| File | What it is |
|---|---|
| `crash.txt` | native signals (SIGSEGV/SIGABRT/... with fault address + pc) and Java stack traces, appended |
| `engine-last.txt` | the exact launch parameters of the last attempt |
| `logcat-*.txt` | logcat dump captured at launch (newest 3 kept) |

The native trap is async-signal-safe (open+write only, no malloc) and lives
in `libtier2jni.so` (`android-app/jni/guard.cpp`); it is armed from
`Hsg100App.onCreate()` before anything engine-related runs. A crash is
re-raised after recording, so Android's own reporting is unaffected.

**If the game closes by itself: open Diagnostics → crash report — it says why.**

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


## Changelog

- **v0.6.0** — TC first aid: argv-safe folder rename (the engine's Android
  argv tokenizer has no quoting — spaces in `-game` were fatal), gameinfo
  regeneration for hand-dropped PC mods, Ep1/Ep2 fallback search paths,
  null-guarded gameinfo pre-scan, EngineHost refuses argv-unsafe launches.
  Badge "WILL RENAME ON LAUNCH" in the catalog. Extra args must stay
  single-token (same tokenizer constraint).
- **v0.5.1** — black screen fixed: engine fonts extracted to filesDir before
  every launch (the menu was alive but could not draw a letter).
- **v0.5.0** — new engine inside (v1.17.0025 aarch64/GLES3, the AruMoon
  generation), Java glue compiled from source, Android 11-14 permission gates.
- **v0.4.x** — ONE APK (engine bundled), launch preflight gates, crash guard
  + watchdog + post-mortem logcat pipeline.
- **v0.3.0** — Phase 3 launcher alpha (catalog, tier2 classifier, import).
