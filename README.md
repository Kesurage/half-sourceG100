# half-sourceG100

Half-Life 2 and Source SDK 2013-lineage mods running natively on Android, with real PC mod compatibility — on a budget MediaTek phone (Infinix Hot 50 4G).

Not a port of one game. A **compatibility fork**: the engine, a mod-compatibility layer, and a launcher so PC mods (including DLL mods, where possible) run without manual surgery, texture errors, or "going through hell."

**This is the Source Engine itself, running natively on Android** — not a Winlator/Wine/proton-style translation project. The engine and game code compile as native ARM64 and talk to the GPU directly (GLES today, panvk/Vulkan later). The only place a translation layer ever appears is the Tier-3 last resort for closed-source Windows DLL mods, clearly separated from the native path.

**Native resolution, always.** The game renders at the panel's native resolution; performance work targets engine and asset cost, never pixel count.

## Target device

Infinix Hot 50 4G — MediaTek Helio G100 (MT6789H), Mali-G57 MC2 (Valhall, JM/kbase kernel driver), 6–8 GB RAM, Android 14. One device, optimized hard for it.

## Scope

**In:** HL2 / EP1 / EP2 base content, Source SDK 2013 mods, Mapbase mods (e.g. Entropy : Zero 2), Workshop-style content addons.
**Out:** engines that are a different world — Black Mesa (heavily forked engine), Source 2 / HL:Alyx, GoldSrc games.

## Architecture (3 tiers, best quality first)

| Tier | Approach | Covers | Status |
|---|---|---|---|
| 1 | **Native ARM64 mod builds** — compile mod `client`/`server` code from source against this fork | EZ2 and every mod that publishes source | Planned |
| 2 | **Content-only mount** — extract VPK/maps/materials/sound, strip Windows exe/dll, inject `gameinfo.txt` search paths, auto-fallback to base HL2 code | Maps, skins, models, most Workshop addons, DLL-only mods (content part) | Implemented + tested (rev 4) |
| 3 | **Translation fallback** — Wine + Box64 + DXVK/D8VK components (from Winlator-Mali ecosystem) inside the app, only for closed Windows DLL mods with no alternative | Rare cases | Last resort |

## Graphics

- Shipping now (v0.5.0): bundled **v1.17 engine, aarch64 + togl GLES3 at native resolution** — the modern nillerusr-lineage build (same generation as AruMoon's "Source Engine for Android"), swapped in after the old 32-bit port proved unfixable (startup hang -> ANR on Android 14).
- Phase 3: Vulkan via **panvk on the stock kbase kernel** — proven feasible by [FristOneRR/FristOneRR-Panvk-Driver](https://github.com/FristOneRR/FristOneRR-Panvk-Driver) (Mali-G57, no root). Clear plan and hooks: `docs/PHASE3-VULKAN.md`.
- BCn texture handling: Mali lacks BC1–7 hardware decode; reuse/learn from WinlatorMali's BCn→ASTC/ETC2 emulation layer.

## Repository layout

- `docs/roadmap.md` — phased development plan, milestones, risks
- `docs/mod-compatibility-pipeline.md` — the Tier 1/2/3 pipeline spec
- `docs/mount-architecture.md` — engine-as-launcher, dual-root mounting (TCs at `srceng/<tc>/`, regular mods auto-scanned from `hl2/custom/`), path mapping, incompatibility matrix
- `docs/renderer-risk.md` — GLES/Mali-G57 renderer risk table + Vulkan/panvk assessment
- `docs/PHASE3-VULKAN.md` — Phase 3 clear instructions: panvk delivery, Zink experiment, native Vulkan track, acceptance matrix
- `docs/ENGINE-PACK.md` — the bundled engine: startup contract, requirements, upgrade path
- `docs/tier2-implementation.md` — tier2 library layout, contracts, test commands
- `docs/launcher.md` — launcher APK guide (install, permissions, import, diagnostics)
- `docs/research-notes.md` — ecosystem research with sources
- `docs/device-target.md` — hardware target and profiling plan
- `scripts/` — build environment setup and build scripts (Phase 0)
- `android-app/` — THE app: mod manager (TC/regular catalog, tier2 JNI classifier, on-device Tier-2 import) + the bundled engine, one APK
- `.github/workflows/build-launcher.yml` — CI: builds both APK flavors and attaches them to releases on `v*` tags
- `tier2/` — mod-compatibility C++ library (classifier, KeyValues, VPK, mount planner); `tools/tc_extractor.py` — PC/CI extraction pipeline; `tests/`

## Releases

- **v0.6.4 — the TC black-screen renderer fix + proven base mounts** — with v0.6.3 the TC mod stopped crashing and got its base content, but the screen still showed nothing while the engine ran (menu sounds, input, even a map load behind the black screen). The on-device watchdog told the story: an endless `BLASTBufferQueue: acquireNextBufferLocked: Failed to acquire a buffer, err=NO_BUFFER_AVAILABLE` flood — **the engine never presented a single frame to the visible surface**. Two fixes ship: (1) *surface-stability gate* — the vendored SDLActivity no longer starts the engine thread on the FIRST ready surface; during the portrait→landscape settle Android destroys and re-creates the SurfaceView's surface, and an engine that binds EGL to the dying surface #1 swaps every frame into a dead window (SDL 2.0.17 only rebinds when the engine pumps window events — which a Source engine deep in mod init does not). The engine now starts only after the surface has been quiet for 700 ms (hard timeout 3.5 s), so EGL binds to the final window. (2) *mount planner rev 7: base mounts spliced from the device's own `hl2/gameinfo.txt`* — the exact, proven-working mount list of the game that renders correctly, instead of re-deriving it from our own VPK validator (which had a real-VPK edge case in every revision so far; the v0.6.3 device log still showed base sounds missing). Wildcards and `..` are never spliced, every file must exist, and a gameinfo we generated ourselves is not trusted as a source. Plus: the TC's `cfg/config.cfg` is seeded from hl2's on first launch (no fresh-defaults video churn), `logs/gameinfo.last.txt` snapshots the exact gameinfo the engine read, the watchdog reads the ACTIVE game dir's console.log (v0.6.3 read hl2's and showed a stale session), and the BLASTBufferQueue flood is collapsed to a count in the watchdog logcat tail so real lines survive. Honest correction of the v0.6.3 notes: the two engine-fork patches (bounds-checked `GetFileList`, never-fatal background art) are source patches in the fork — the shipped APK still carries the pinned v1.17.0025 binaries; the crash fix holds because the launcher never mounts an unvalidated VPK. 137 Java tests green.
- **v0.6.3 — TC mods get their base content back (the missing-background fix)** — MINERVA and Lost Coast (and every TC) died at a fatal "Can't find background image" because rev 5's deep VPK validation demanded a `0x0000` directory-entry terminator — but every real Steam VPK (and the engine's own writer: `packedstore.cpp` `PACKFILEINDEX_END`) uses **`0xffff`**. All 16 base archives were therefore falsely rejected on-device (`base VPKs rejected (not mounted)` in logcat), TCs mounted zero base HL2 content, and the engine's startup-art loader turned missing console art into a fatal `Error()`. Rev 6: the validator walks the REAL entry layout (part descriptors until the `0xffff` terminator, multi-part entries included; the bounds checks, not the terminator value, carry the safety), the planner marker is bumped to **rev 6** so every broken rev-5 gameinfo regenerates on sight, and the Python twin drops its remaining `*.vpk` wildcard globs (the v0.6.1 crash class) for loose-dir fallbacks — the launcher adds exact validated mounts on-device. The engine fork carries two hardening fixes: (1) `CPackedStore::GetFileList` now bounds-checks every string against the tree end and formats with `V_snprintf` instead of raw `sprintf` into `MAX_PATH` buffers — the "FORTIFY: vsprintf: prevented 77844-byte write into 260-byte buffer" SIGABRT (the LoadPlugins FindFirst scan) cannot happen anymore, verified against the shipped code under ASan/UBSan with the exact repro plus 1 MB of garbage; (2) missing startup art is no longer fatal: the background loader tries `<bg>_widescreen` → `<bg>` → `background01_widescreen` → `background01` and continues without art instead of `Error()`-ing out; a missing `startup_loading.vtf` only skips the corner overlay. Test fixtures now write real `0xffff` terminators (the v0.6.2 suite tested the model, not reality — that's why it stayed green while the device broke): 125 Java + 20 Python + C++ suite green, plus the ASan walk harness. Known cosmetic noise, not a bug: "Requested non-existent write path MOD" lines in logcat — the engine falls back to the first writable search path (the TC folder), so saves and configs are written normally.
- **v0.6.2 — TC crash fixed** — mount planner rev 5: no wildcards; the launcher enumerates the base VPKs that actually exist and deep-validates each (signature, header-vs-filesize truncation guard, full bounds-checked tree walk) before emitting exact filenames, so a corrupt/truncated archive (the v0.6.1 SIGABRT crash class in `CPackedStore::GetFileList`) can never be mounted. (The validation itself had the v0.6.3 bug: it rejected real VPKs too.)
- **v0.6.1 — TC mods render** — fixed the v0.6.0 "launches but does not render" black screen. Root cause (verified against the pinned engine source): a SearchPaths `.vpk` entry is opened AS THE EXACT FILENAME GIVEN (`AddVPKFile` → `CPackedStoreRefCount(name)`), and a missing file is dropped silently — our rev-3 gameinfo generation stripped the `_dir` suffix, so TC mods mounted ZERO base HL2 VPKs (1936 `Missing Vgui material` lines, no UI sounds, broken menu). Mount planner rev 4: the mod's own VPKs are listed by real filename (`_dir` kept) and base content mounts through the engine-native `|all_source_engine_paths|hl2/*.vpk` wildcard globs (absolute-path `FindFirst`, chunk archives auto-deduped by the engine), so whatever VPK layout hl2/episodic/ep2/platform actually have gets mounted. Old generated gameinfos are upgraded on sight (rev marker). All four planner twins (C++/Java/Python + tests) updated; 116 Java + 19 Python + C++ suite green.
- **v0.6.0 — TC mods actually launch** — every TC mod whose folder name contains a space crashed at startup: the engine's Android argv tokenizer splits on spaces with no quoting, so `-game Minerva Metastasis` became `-game Minerva` + junk and the engine died on a missing gameinfo before the first frame (base games never have spaces — that's why they worked). Launch-time TC first aid: argv-safe folder rename, gameinfo regeneration by the C++ mount planner (original kept as gameinfo.pc.txt), Ep1/Ep2 fallback search paths, null-guarded gameinfo pre-scan, argv-safety refusal in EngineHost. Engine fork carries the quote-aware tokenizer (56eeb46) for the next engine-pack build. Carries the v0.5.1 font fix (black-screen fix).
- **v0.5.0 — NEW ENGINE INSIDE** — the bundled engine is now the modern v1.17.0025 aarch64/GLES3 build (AruMoon's engine generation) instead of the old 32-bit port that hung on Android 14. The engine's Java glue is compiled from source against our resources (no more foreign-R-id crashes), the Android 11-14 storage/mic permission gates are fixed, and the arm64 tier2 classifier + crash guard ride along. Installs over v0.4.x.
- **v0.4.0 — ONE APK: the engine is in** — the launcher and the engine ship together (no separate engine app, no shell). The mod manager drives the bundled old-port Source engine through its real startup contract (SDLActivity + ValveActivity2 glue, d8-merged dex, `lib/armeabi-v7a` engine set + assets bundled). Base games launch through the official game table; TCs via `-game` with lineage fallback; regular mods via `hl2/custom/`. Engine launch tuning (argv/env) in Settings; Phase-3 Vulkan plan and hooks documented (`docs/PHASE3-VULKAN.md`). Fixes over v0.3.0: the tier2 classifier native lib shipped at a broken path and never loaded; the dead engine-surface shell and external-engine delegation were removed. Install guide: `docs/launcher.md`.
- **v0.3.0 — Phase 3 launcher alpha**: the mod catalog APK. Dual-root scanner (TC vs regular mods), tier2 classifier badges + honest warnings, on-device Tier-2 import from PC mod zips (KEEP/STRIP/FIX contract; gameinfo generated by the C++ mount planner with rev-3 engine-valid tokens), enable/disable via `hl2/custom_disabled/`, device diagnostics, settings.

## Reference forks (in this account)

- [Kesurage/source-engine](https://github.com/Kesurage/source-engine) — fork of nillerusr/source-engine, the base engine
- [Kesurage/Valve-on-android](https://github.com/Kesurage/Valve-on-android) — Steam content download approach (legal on-device depot download)
- [Kesurage/FristOneRR-Panvk-Driver](https://github.com/Kesurage/FristOneRR-Panvk-Driver) — panvk-on-kbase driver packaging for Mali-G57

## Legal

Engine lineage is the leaked Source 2017/2018 tree (nillerusr fork). Not for commercial use. You must own your games on Steam; content is pulled or converted from your own copies.
