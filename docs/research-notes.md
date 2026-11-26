# Research notes — sources and findings

Working session of 2026-11-26. Each finding lists where it came from; verify links before relying on them.

## Base engine

- **nillerusr/source-engine** — https://github.com/nillerusr/source-engine. Source 2017/2018-leak lineage ported to Android/ARM64. Features: touch support, BSP v19–21, MDL v46–49, VTF 7.5, PBR, 64-bit, modern toolchains. Roadmap: materialsystem rewrite for GL, dxvk-native. This is the base for half-sourceG100.
- Android build path (from the wiki): NDK r10e + clang 11, waf `./waf configure --togles --android=aarch64,host,21 -8 -T release`. Old toolchain but known-good.
- **AruMoon** — https://github.com/AruMoon. `source-engine` fork of nillerusr, `source-engine-dedicated` (archived dedicated-server branch), `Valve-on-android` (fork of kennedcandido/valve-on-android): scripted legal on-device download of owned Valve game content. Useful for our launcher's content step. All forks copied into this account for reference.

## Proof that big TC mods run natively on Android

- **GDroid (tinsarfal/GDroid)** — https://github.com/tinsarfal/GDroid. Garry's Mod native on Android, ARM64+armv7 APKs, Steam content download in-app, save backup, in-place updates. Closed source (APK releases only) — technique proven, launcher must be rebuilt by us. Same author runs **SourceVR** standalone ports of HL2/EZ2/Portal for standalone headsets → confirms "big TC mod → native standalone build" works on the nillerusr engine.
- **Entropy : Zero 2 source** — https://github.com/entropy-zero/source-sdk-2013, branch `ez2/mapbase`. Public SDK2013/Mapbase code → Tier 1 native build is possible for EZ2. Also **z33ky's Linux port** of EZ2 (github.com/z33ky/source-sdk-2013) proves the codebase compiles off-Windows.
- **Mapbase** — Mapbase-source/source-sdk-2013: the 2020s mod platform. One Mapbase compat layer covers many modern mods.

## Graphics: panvk on the exact target GPU

- **FristOneRR/FristOneRR-Panvk-Driver** — https://github.com/FristOneRR/FristOneRR-Panvk-Driver. Mesa panvk Vulkan driver built for **Mali-G57 (Valhall) running on the stock Arm kbase (JM) kernel driver**, packaged as a Winlator driver zip, no root. Beta releases (Panvk-Mali-G57, v3.x). Discussion: r/EmulationMediatekMali. → Vulkan on the Hot 50 4G is feasible without kernel surgery.
- Mesa panvk context: Vulkan 1.2 conformant on Mali-G610 (v10) in Mesa 25.x; G57 (v7-gen2/Valhall JM) supported via these community builds.

## Winlator-Mali ecosystem (Tier 3 donor components)

- **GunaCharanTeja/WinlatorMali** (branch `bionic-mali-1.2`, final 1.2.1, discontinued → successor DXML): Wine + Box86/64 + FEX on **Bionic** runtime, Mali "Wrapper" driver with BCn emulation, Turnip+DXVK for Adreno.
- Directly reusable pieces: `android_sysvshm` (Wine shm), `audio_plugin`, **BCn→ASTC/ETC2 emulation with persistent cache (leegao)** — relevant even for our native GLES/Vulkan path because Mali lacks BCn hardware — input overlay system, HUD/telemetry.
- Licenses: MIT app, but Wine/VKD3D are LGPL (dynamic linking + compliance), libadrenotools GPL — audit before embedding.

## Prior verdicts (still standing)

- DXVK for the native engine is irrelevant short-term (Source talks GL ES natively; DXVK needs a Windows/D3D context). It matters only inside the Tier 3 Wine container or via dxvk-native later.
- Out of scope: Black Mesa (forked engine), HL:Alyx (Source 2), GoldSrc titles.

## Open questions

1. Does the FristOneRR panvk zip work inside a non-Winlator app (its packaging vs. a plain ICD load)? Test in Phase 4.
2. VPK streaming memory behavior on 6 GB devices with heavy TC content (EZ2 maps are large).
3. Mapbase's SDK2013 deltas vs nillerusr engine diffs — size the patch layer in Phase 2.
