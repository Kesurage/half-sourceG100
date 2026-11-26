# Device target — Infinix Hot 50 4G

One device, optimized hard. All presets and profiling assume this hardware.

## Hardware

| Item | Value | Implication |
|---|---|---|
| SoC | MediaTek Helio G100 (MT6789H) | 2× Cortex-A76 + 6× A55; big.LITTLE pinning matters |
| GPU | Mali-G57 MC2 (Valhall, JM) | ~2 shader cores; memory-bandwidth-bound; no BCn hardware decode |
| Kernel GPU driver | Arm **kbase** (stock) | panvk must target kbase (FristOneRR approach), not panthor |
| RAM | 6–8 GB | Enough for HL2 content if VPK streaming is tuned |
| OS | Android 14 (XOS) | Scoped storage → content under app dirs or SAF-picked storage |
| Display | 720p-class, high refresh | Render at panel native resolution — fixed, never scaled |

## Profiling plan (start of Phase 1)

1. Baseline map: `d1_trainstation_01` and one combat map (`d1_canals_01`), fixed camera path.
2. Measure: fps, frame time distribution, GPU busy %, memory bandwidth proxy, shader-compile stutter count.
3. Change one variable at a time: texture quality → shadow/particle presets → thread pinning. Resolution is **never** a variable; it stays at panel native.
4. Store results per build in `docs/benchmarks/` (committed after each profiling run).

## Non-negotiables on this device

- Ship shader cache prewarmed at first launch (Mali shader compile stutter is a top complaint).
- BCn textures must be transcoded at install time (ASTC or RGBA), not per-frame emulated, whenever possible — the WinlatorMali cache approach is the fallback for on-the-fly cases.
- **Native resolution, no exceptions.** The engine renders at the panel's native resolution; no dynamic res, no render-scale. Performance headroom comes from asset/shader budgets, not pixel count.
- Shader cost discipline on a 2-core Mali; fps target 30, floor 25 — reached by reducing effects complexity, never by scaling the framebuffer.
