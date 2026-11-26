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
| 2 | **Content-only mount** — extract VPK/maps/materials/sound, strip Windows exe/dll, inject `gameinfo.txt` search paths, auto-fallback to base HL2 code | Maps, skins, models, most Workshop addons, DLL-only mods (content part) | Implemented + tested (rev 3) |
| 3 | **Translation fallback** — Wine + Box64 + DXVK/D8VK components (from Winlator-Mali ecosystem) inside the app, only for closed Windows DLL mods with no alternative | Rare cases | Last resort |

## Graphics

- Shipping now: bundled old-port engine, GLES2 at native resolution (works everywhere).
- Phase 2: togles GLES3 (aarch64 new-port engine from CI).
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

- **v0.4.0 — ONE APK: the engine is in** — the launcher and the engine ship together (no separate engine app, no shell). The mod manager drives the bundled old-port Source engine through its real startup contract (SDLActivity + ValveActivity2 glue, d8-merged dex, `lib/armeabi-v7a` engine set + assets bundled). Base games launch through the official game table; TCs via `-game` with lineage fallback; regular mods via `hl2/custom/`. Engine launch tuning (argv/env) in Settings; Phase-3 Vulkan plan and hooks documented (`docs/PHASE3-VULKAN.md`). Fixes over v0.3.0: the tier2 classifier native lib shipped at a broken path and never loaded; the dead engine-surface shell and external-engine delegation were removed. Install guide: `docs/launcher.md`.
- **v0.3.0 — Phase 3 launcher alpha**: the mod catalog APK. Dual-root scanner (TC vs regular mods), tier2 classifier badges + honest warnings, on-device Tier-2 import from PC mod zips (KEEP/STRIP/FIX contract; gameinfo generated by the C++ mount planner with rev-3 engine-valid tokens), enable/disable via `hl2/custom_disabled/`, device diagnostics, settings.

## Reference forks (in this account)

- [Kesurage/source-engine](https://github.com/Kesurage/source-engine) — fork of nillerusr/source-engine, the base engine
- [Kesurage/Valve-on-android](https://github.com/Kesurage/Valve-on-android) — Steam content download approach (legal on-device depot download)
- [Kesurage/FristOneRR-Panvk-Driver](https://github.com/Kesurage/FristOneRR-Panvk-Driver) — panvk-on-kbase driver packaging for Mali-G57

## Legal

Engine lineage is the leaked Source 2017/2018 tree (nillerusr fork). Not for commercial use. You must own your games on Steam; content is pulled or converted from your own copies.
