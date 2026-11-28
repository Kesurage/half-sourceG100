# Phase 3 — Vulkan rendering on Mali-G57 MC2 (panvk)

Clear instructions, current state, and the exact plan. Target device:
Infinix Hot 50 4G (Helio G100, Mali-G57 MC2, Android 14, stock kernel — no root).

## 0. Where we are right now (honest status)

| Piece | State |
|---|---|
| Engine running on-device | YES — bundled old-port engine (GLES2 via Regal, armeabi-v7a) ships inside the launcher APK as of v0.4.0 |
| Renderer today | GLES 2.0 surface at NATIVE panel resolution. Works, but it is a 2013-era GL2 path: no sRGB, limited fill-rate headroom on a 2-core Mali |
| Vulkan on device | System Vulkan loader + proprietary Mali driver exist (`/vendor/lib64/hw/vulkan.*.so`), **not usable by us for a native Source renderer path yet** |
| panvk (Mesa) | Userspace, works on the stock kbase kernel driver. Mali-G57 is Valhall (v9) — panvk support for v9 is real but younger than Midgard/Bifrost. It must be validated per-device |
| Engine-side Vulkan backend | Not in the bundled build. The new-port engine tree (nillerusr master) lists `dxvk-native` as a current task — the natural bridge |

**Phase 3 goal:** a Vulkan render path that keeps the game at native resolution and
keeps the mod-compatibility tiers untouched. GLES stays the default until the
Vulkan path passes the acceptance matrix below.

## 1. Architecture decision — three tracks, ordered

### Track A — panvk driver delivery (launcher side, no engine change)
Ship a Mesa panvk build inside the APK (`files/vulkan/`), selectable in the
launcher:

```
assets/vulkan/
  libvulkan_panvk.so          # Mesa panvk ICD driver
  panvk_icd.aarch64.json      # ICD manifest
```

Launcher wiring (implemented as the "engine environment" setting + `EngineHost`):

```
VK_ICD_FILENAMES=/data/data/<pkg>/files/vulkan/panvk_icd.aarch64.json
```

Steps:
1. Build Mesa for aarch64 with `-Dvulkan-drivers=panfrost` (and `-Dgallium-drivers=zink`
   for Track B). Cross-compile with the NDK clang toolchain; the panvk kbase
   interface must match the device kernel's kbase version (check
   `/proc/device-tree/gpu*/` or `dmesg | grep kbase` on-device).
2. Package the ICD + driver into the APK assets; the launcher extracts them to
   `files/vulkan/` on first run.
3. Validate: `vulkaninfo` equivalent from a tiny probe APK — enumerate device,
   queue families, formats. Record results in Diagnostics.

### Track B — Zink (GL-on-Vulkan) over panvk — the low-risk experiment
Run the EXISTING GLES renderer through Mesa Zink on top of panvk:

```
LIBGL_ALWAYSBIND=0 GALLIUM_DRIVER=zink LIBGL_DRIVERS_PATH=<bundled mesa>
VK_ICD_FILENAMES=<panvk icd>
```

This needs zero engine code changes — it is a driver-stack swap. Expected
outcome on G57 MC2: correctness unknown, CPU overhead meaningful (2×A75+A55),
but it is the cheapest way to (a) prove panvk works on the device, and (b) get
GL 3.x/4.x features the GLES2 path lacks. Ship behind an "Experimental
renderer: Zink/Vulkan" toggle in Settings, with automatic fallback to GLES.

### Track C — native Vulkan in the engine (the real Phase 3 deliverable)
The new-port engine (master branch) + `dxvk-native`-style backend, or a direct
`shaderapi` Vulkan backend. This is a fork-level project:

1. Rebase our engine patches onto the current master (aarch64 + togles GLES is
   the default renderer there; it already runs natively at 64-bit).
2. Either:
   - **C1:** run the engine's D3D9 shaderapi through `dxvk-native` (the repo's
     own roadmap item) on panvk — D3D9→Vulkan, battle-tested on Linux, or
   - **C2:** write `shaderapi_vk` natively (largest effort, best long-term).
3. Land per-piece: swapchain → buffers/textures → pipelines → shaders (SPIR-V
   via glslang) → present. GLES toggle remains the fallback.

## 2. Acceptance matrix (before Vulkan becomes default)

| Test | Pass condition |
|---|---|
| d1_mainmenu | Main menu renders, no artifacts, native res |
| d2_train | `d1_trainstation_01` loads and plays 60s |
| d3_canals | Water + particles visible (the classic breakage) |
| d4_perf | ≥ 30 fps sustained in d1 canals street scene |
| d5_stability | 30 min soak, no driver crash / ANR |
| m1_mods | One Tier-2 content mod mounted during d2 |

## 3. Why not today (constraints you should know)

- panvk for Valhall v9 (G57) has come far but is still younger than the
  Midgard/Bifrost lines; the device's stock kbase version must be matched by
  the Mesa build (panvk tracks the mainline kbase interface).
- The bundled old-port engine is GLES2-only (Regal); it cannot consume Vulkan
  directly — Track C requires the new-port engine, which is the Phase-2
  CI-build product.
- 32-bit note: the bundled engine is armeabi-v7a. Track C (new-port) is
  aarch64 — that also moves us off 32-bit.

## 4. What v0.4.0 already ships for Phase 3

- Launcher environment passthrough (`Settings → Engine environment`) — the
  exact hook Tracks A/B need (env vars reach the engine via `setEnvs`).
- Diagnostics screen: Vulkan feature flags of the device (version, 1.1, 1.3,
  compute, level) — the pre-flight check for panvk experiments.
- Native resolution is enforced end-to-end (engine renders at the SDL surface
  size; no scaling code exists on the path).
- The repo build pipeline produces the merged engine APK reproducibly
  (`scripts/make-launcher-apk.sh --with-engine`).

## 5. Milestones

- **M0 (done)** — engine runs, GLES2, native res, mod manager integrated.
- **M1** — panvk + Zink bundled build boots Track B on-device; collect
  diagnostics.
- **M2** — new-port engine (aarch64) build lands from CI; togles GLES3 becomes
  the default renderer; old-port stays as fallback.
- **M3** — Track C1 (dxvk-native over panvk) passes the acceptance matrix.
- **M4** — decision point: Vulkan default-on for this device, GLES fallback.
