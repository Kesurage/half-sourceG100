# half-sourceG100

Half-Life 2 and Source SDK 2013-lineage mods running natively on Android, with real PC mod compatibility — on a budget MediaTek phone (Infinix Hot 50 4G).

Not a port of one game. A **compatibility fork**: the engine, a mod-compatibility layer, and a launcher so PC mods (including DLL mods, where possible) run without manual surgery, texture errors, or "going through hell."

## Target device

Infinix Hot 50 4G — MediaTek Helio G100 (MT6789H), Mali-G57 MC2 (Valhall, JM/kbase kernel driver), 6–8 GB RAM, Android 14. One device, optimized hard for it.

## Scope

**In:** HL2 / EP1 / EP2 base content, Source SDK 2013 mods, Mapbase mods (e.g. Entropy : Zero 2), Workshop-style content addons.
**Out:** engines that are a different world — Black Mesa (heavily forked engine), Source 2 / HL:Alyx, GoldSrc games.

## Architecture (3 tiers, best quality first)

| Tier | Approach | Covers | Status |
|---|---|---|---|
| 1 | **Native ARM64 mod builds** — compile mod `client`/`server` code from source against this fork | EZ2 and every mod that publishes source | Planned |
| 2 | **Content-only mount** — extract VPK/maps/materials/sound, strip Windows exe/dll, inject `gameinfo.txt` search paths, auto-fallback to base HL2 code | Maps, skins, models, most Workshop addons, DLL-only mods (content part) | Planned |
| 3 | **Translation fallback** — Wine + Box64 + DXVK/D8VK components (from Winlator-Mali ecosystem) inside the app, only for closed Windows DLL mods with no alternative | Rare cases | Last resort |

## Graphics

- Phase 1: engine's native OpenGL ES renderer (`--togles`), tuned for Mali-G57.
- Phase 2: Vulkan path via **panvk on the stock kbase kernel** — proven feasible by [FristOneRR/FristOneRR-Panvk-Driver](https://github.com/FristOneRR/FristOneRR-Panvk-Driver) (Mali-G57, no root).
- BCn texture handling: Mali lacks BC1–7 hardware decode; reuse/learn from WinlatorMali's BCn→ASTC/ETC2 emulation layer.

## Repository layout

- `docs/roadmap.md` — phased development plan, milestones, risks
- `docs/mod-compatibility-pipeline.md` — the Tier 1/2/3 pipeline spec
- `docs/research-notes.md` — ecosystem research with sources
- `docs/device-target.md` — hardware target and profiling plan

## Reference forks (in this account)

- [Kesurage/source-engine](https://github.com/Kesurage/source-engine) — fork of nillerusr/source-engine, the base engine
- [Kesurage/Valve-on-android](https://github.com/Kesurage/Valve-on-android) — Steam content download approach (legal on-device depot download)
- [Kesurage/FristOneRR-Panvk-Driver](https://github.com/Kesurage/FristOneRR-Panvk-Driver) — panvk-on-kbase driver packaging for Mali-G57

## Legal

Engine lineage is the leaked Source 2017/2018 tree (nillerusr fork). Not for commercial use. You must own your games on Steam; content is pulled or converted from your own copies.
