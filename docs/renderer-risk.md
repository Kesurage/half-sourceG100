# Renderer risk assessment — GLES on Mali-G57 (Hot 50 4G)

Shipping renderer: **togles** (ToGL → OpenGL ES 3) inside the nillerusr
source-engine fork. Vulkan (panvk on stock kbase) stays the Phase 4
add-on, never the default. This doc lists what will actually bite us on a
2-core Valhall GPU with no BCn hardware, and the plan for each.

## Confirmed problem history on this exact engine/GPU family

| Symptom | Where seen | Read |
|---|---|---|
| Water reflection flickering (also CSS) | nillerusr Source Engine v1.13, Mali GPU, community video + reports | render-target/framebuffer handling on Mali tiler — treat as *likely* defect in our FBO path, not shader magic |
| "Filter Visuals look horribly wrong", map-load crashes | nillerusr/source-engine issue tracker | engine hardening checklist for Phase 1 |
| Shader precision artifacts on mobile (mediump world-pos/UV math) | known GLES class issue | audit togles shaders for mediump on positional math; force highp where needed |
| Generic GLES3 perf anti-patterns | Arm Mali best-practice guides | engineering discipline list below |

## Hard constraints of Mali-G57 MC2 (from device-target.md)

- 2 shader cores, **memory-bandwidth-bound** → texture format and vertex
  format decisions dominate fps.
- **No BCn hardware decode.** Every DXT-encoded VTF inside a mod VPK must be
  transcoded. This is renderer-independent: it applies to GLES *and* Vulkan
  paths.
- ASTC decodes in hardware → ASTC is the preferred transcode target
  (bandwidth per pixel ~¼–⅛ of RGBA8).
- Native resolution always; headroom comes from asset/shader budgets only.

## Risk table + mitigations

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| 1 | BCn textures in mod VPKs | **Blocker if ignored** | Import-time transcode DXT1→ASTC LDR, DXT5→ASTC with alpha; RGBA8 fallback only for pathological formats. Persistent cache keyed by (vpk path, entry crc, format). Reuse the WinlatorMali/leegao cache *concept*, not code (license audit). `astcenc` aarch64 runs on-device in a background import job; base HL2 content ships pre-transcoded. |
| 2 | Water/reflection render-target flicker (reported on this engine + Mali) | High — core HL2 maps use water heavily | Phase 1 hardening item: bisect FBO resolve path; ship quality preset that lowers reflection RT size/count while keeping native framebuffer resolution (that's an *asset* budget, not a resolution cheat) |
| 3 | Shader-compile stutter | High on Mali (device doc: top complaint) | Prewarm shader cache at first launch; keep pipeline cache file per game dir; never compile inside frame |
| 4 | mediump precision artifacts | Medium | Shader audit: highp for world-space/UV-combining math; verify on G57 in Phase 1 profiling maps |
| 5 | Mid-frame texture uploads (stall tiler) | Medium | Engine streams VTFs — enforce Arm guidance: uploads at load/frame-prepare only; cap in-flight upload queue |
| 6 | `glReadPixels` hot paths (screenshots, debug overlays) | Low-Medium | Strip/disable in release; route debug overlays through text/HUD layer |
| 7 | Fat vertex formats / redundant state churn | Medium (bandwidth-bound GPU) | Audit degenerate vertex decls in port; pack attributes; batch world decals |
| 8 | MSAA cost on tiler with big RTs | Medium | Default off; FXAA post path; if MSAA used, Arm-guide tile-friendly configs only |
| 9 | panvk-on-kbase immaturity (Vulkan path) | Low (not shipping path) | Phase 4 experiment only; GLES remains default; FristOneRR builds prove feasibility on this exact GPU, packaging-in-plain-app still open (research notes Q1) |
| 10 | Engine branch bugs (map-load crash, filter visuals) | Medium | Tracked as engine hardening in Phase 1; fix upstream in our fork, note patches in port registry |

## Engineering discipline list (from Arm Mali guides, enforced in PR review)

- Load-time-only texture uploads; immutable storage where GLES 3.1 offers it
- Write-only buffer maps, minimal attribute sizes, no per-frame allocations
- One variable at a time in profiling; results committed to `docs/benchmarks/`

## Decision

GLES ships. The renderer's make-or-break items are (1) the BCn→ASTC import
pipeline and (2) the reflection/FBO flicker fix — both are Phase 1 work
items, both testable on the device without Vulkan. Vulkan/panvk remains a
contained Phase 4 experiment; if it doesn't beat GLES fps, it's dropped
without blocking mod compat (unchanged roadmap gate).

## Sources

- nillerusr/source-engine repo + issue tracker (GLES path, known bugs)
- Community reports: v1.13 Mali water-reflection flicker; ToGL→GLES3 port notes
- Arm Mali GPU Best Practices / OpenGL ES 3.x developer guides (uploads, buffers, ASTC, MSAA)
- `docs/device-target.md`, `docs/research-notes.md` (FristOneRR panvk on kbase, WinlatorMali BCn emulation cache)
