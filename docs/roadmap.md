# Roadmap — half-sourceG100

Goal: HL2 + Source SDK 2013 / Mapbase mods running on the Infinix Hot 50 4G (Helio G100, Mali-G57 MC2) with maximum PC mod compatibility. Speed and quality both matter; mistakes on this device are expensive, so each phase has a verification gate before the next starts.

**Ground rules (set by the project owner):**
- This is the **native Source Engine on Android** — not a Winlator/Wine fork. Everything ships as native ARM64 code; translation containers are a last-resort compat path for closed Windows DLL mods only, never the main runtime.
- **Native resolution, always.** The display renders at the panel's native resolution; no dynamic resolution scaling, no upscaling, no render-scale tricks. Performance comes from engine-side optimization (draw calls, shader cost, asset budgets), not from rendering fewer pixels.

## Phase 0 — Environment and baseline (week 1)

Deliverables:

1. Working cross-build of `nillerusr/source-engine` for `aarch64`:
   - Linux host, Android NDK r10e + clang 11, waf: `./waf configure --togles --android=aarch64,host,21 -8 -T release`
   - Produce engine .so set + a minimal APK shell (activity + SDL/togles surface) that loads them.
2. HL2 content on device: extract from own Steam copy (DepotDownloader on PC, or the Valve-on-android script approach), verify boot into main menu and d1_trainstation_01 loads.

Gate: 15+ fps in the intro map at low settings, no missing textures.

## Phase 1 — Engine hardening for G100 (weeks 2–4)

1. Profile and fix: 2×A76 + 6×A55 scheduling, single big-thread mode, reduce texture streaming pressure (6–8 GB RAM but memory-bandwidth-bound Mali).
2. Mali-G57 GLES fixes: BCn→fallback decode path (Mali has no BCn hardware), NPOT texture handling, shader cache to skip per-launch compile stutter.
3. Touch controls: basic layer first (GDroid-style layout can come later), + optional gamepad.
4. Performance knobs: `-novid`, particle/lightmap quality presets baked into a `G100` preset. **Resolution stays native** — the preset only touches shader/asset cost, never the render target size.

Gate: stable 25–30 fps in playable maps, no crashes across d1_ chapters.

## Phase 2 — Mod compatibility layer (weeks 4–8)

The core differentiator. See `mod-compatibility-pipeline.md` for the spec.

**Status: in progress — items 1–3 implemented and tested (see `tier2/` and `tools/tc_extractor.py`), item 4 pending a Linux build host. Mounting model revised: ONE engine-as-launcher APK, dual-root mounting (TCs at `srceng/<tc>/` launched via `-game`, regular mods auto-scanned from `hl2/custom/`) — spec in `mount-architecture.md`. Renderer risks (BCn→ASTC, water-reflection flicker, shader precision) assessed in `renderer-risk.md`.**

1. **Mount engine**: multi-search-path `gameinfo.txt` generation; mount arbitrary VPKs/folders as mods without reinstalling.
   - DONE: `tier2/src/mountplanner.cpp` (C++, engine-side) + `tools/tc_extractor.py` (Python twin, PC/CI-side). Mod content mounted first, base HL2 appended last as the fallback that kills texture errors. Round-trip parses as valid KeyValues (tested).
2. **Content converter**: PC mod folder → sanitized content set (keep `materials/ models/ maps/ sound/ resource/ scripts/` + VPKs; strip `*.exe`, `bin/*.dll`, launcher junk). Works as PC-side Python tool first, then on-device.
   - DONE: `tools/tc_extractor.py` — KEEP/STRIP/FIX contract implemented, VPK signature validation (v1/v2), `manifest.json` report per conversion, `--dry-run`. 14/14 Python tests + e2e CLI run verified.
3. **Mod detector**: identify mod type from its `gameinfo.txt`/binaries — (a) source-available SDK2013/Mapbase mod, (b) content-only, (c) closed Windows DLL mod — and report exactly what will and won't work.
   - DONE: `tier2/src/modclassifier.cpp` — classes EMPTY / BROKEN_GAMEINFO / SOURCE_MOD / CONTENT_ONLY / DLL_ONLY / STANDALONE_TC with honest per-class notes. Python twin in tc_extractor.py. All classifier tests pass on both.
4. **EZ2 reference build**: compile [entropy-zero/source-sdk-2013](https://github.com/entropy-zero/source-sdk-2013) (`ez2/mapbase` branch) client/server for aarch64 against our engine. This is the Tier-1 proof case.
   - PENDING: needs the Phase 0 engine build running first (Linux host).

Gate: EZ2 boots and plays a chapter; a DLL-only mod mounts with full content and no texture errors.

## Phase 3 — Launcher (weeks 8–12)

The launcher IS the engine (one APK, no per-mod standalone builds — see `mount-architecture.md`).

1. Mod catalog with two sections: TC mods (`srceng/<tc>/`, launch via `-game`) and regular mods (`hl2/custom/`, enable/disable, mounted by the engine's custom-folder scan). Per-mod class badge, icon, honest warnings from the tier2 classifier, path mapping handled at import.
2. Content converter UI (on-device extraction using the same tier2 lib), game-content downloader (Valve-on-android approach), settings, save backup per mod (GDroid-style, but ours and open source).
3. On-device mod install from zip/folder; Workshop-style local library.

Gate: a non-technical user can install HL2 content + a mod from the UI and play.

## Phase 4 — Vulkan / panvk path (weeks 12+)

1. Prototype panvk (Mesa) on the stock kbase driver using FristOneRR's packaging approach as a loadable driver, no root.
2. Add a Vulkan materialsystem path or evaluate nillerusr's dxvk-native roadmap item against it.
3. Keep GLES as default; Vulkan as selectable renderer.

Gate: Vulkan path beats GLES fps or is dropped without blocking mod compat.

## Phase 5 — Tier 3 translation fallback (opportunistic)

Wine + Box64 + D8VK/DXVK container (WinlatorMali components: bionic runtime, android_sysvshm, BCn emulation) to load closed Windows `client.dll` mods. Highest effort, lowest priority; only attempted after Phases 2–4 are solid. Most mods never reach this tier because Tier 1 or 2 already covers them.

## Risks

| Risk | Mitigation |
|---|---|
| NDK r10e + clang 11 toolchain is ancient | Isolate in docker/script; it's known-good for this engine — don't modernize until baseline works |
| DLL-only mods that can't be recompiled | Honest detection + content-only fallback; Tier 3 last |
| panvk on kbase immature | GLES stays the shipping renderer; Vulkan is additive |
| Memory pressure on 6–8 GB device | Texture presets, aggressive VPK streaming, 32-bit-safe path kept in mind but arm64 only |
| Legal | Own-content-only, no retail files in APK, no commercial use |
