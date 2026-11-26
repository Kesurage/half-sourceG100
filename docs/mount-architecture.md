# Mount architecture — one engine, dual-root mounting

**Decision (revised): the launcher IS the engine.** One APK, one engine build. No
per-mod standalone APKs — a TC mod runs as a `-game <dir>` switch of the same
engine binary that runs plain HL2. This is the model GDroid proved (its own
content layout: `srceng/garrysmod/` + `srceng/garrysmod/addons/`, local addon
folders dropped in by hand) and the model official Source engines use for
user content (TF2/CSGO/L4D2 auto-scan a `custom/` folder where each VPK and
subfolder becomes a search path that overrides the base game).

## Why two categories exist (it's not cosmetic)

A TC mod **replaces the game config**: it carries its own `gameinfo.txt`,
its own content tree, and (after Tier 1) its own `bin/*.so`. Launching it means
pointing the engine at a different gamedir. A regular mod only **layers
content** over HL2 — no gamedir change, no code change is possible, base HL2
client/server code always runs. Different mechanism, different failure modes,
so the launcher presents two sections and the engine has two mount paths.

## On-device layout (scoped storage: app-private dir, or SAF-picked root)

```
srceng/
├── hl2/                      base HL2 content (user supplies legally)
│   ├── custom/               ← regular mods live HERE (engine auto-scan)
│   │   ├── <addon>/          extracted content-only mod (dirs or loose vpk)
│   │   ├── <addon>.vpk       or a single vpk dropped in
│   │   └── disabled/         launcher-managed: moved here to turn off
│   ├── save/                 base-game saves
│   └── ...
├── platform/
├── <tcname>/                 ← TC mods live HERE (each a full gamedir)
│   ├── gameinfo.txt          generated/extracted by tc_extractor
│   ├── maps/ materials/ ...
│   ├── bin/                  Tier 1 .so builds land here when available
│   └── save/
└── sourceengine/             our engine .so set (engine code, never content)
```

GDroid's layout (`srceng/garrysmod/...`) maps 1:1 onto this with `garrysmod`
replaced by `hl2` + TC dirs. Naming `srceng` is kept for parity with every
prior art (nillerusr, GDroid) so documentation and user habits transfer.

## Mount resolution order (highest priority first)

1. **Active gamedir**: TC dir when `-game <tc>`; otherwise `hl2`
2. `hl2/custom/*` — every enabled addon dir or `_dir.vpk`, alphabetical
   (later entries never override earlier ones; deterministic)
3. Base `hl2` content (skipped when a TC gamedir is active? **No — kept:**
   TCs assume HL2 fallback content, that's the anti-texture-error contract)
4. `platform` (vgui, resource UI fallbacks)
5. Engine defaults

The engine's filesystem must be patched (Phase 0/1 engine work, see issue #3)
to perform the `custom/` scan at filesystem init. The 2017/2018 branch lineage
nillerusr's engine comes from already contains the TF2-era custom-folder
machinery upstream; confirm it survived the port, otherwise re-add
(~50 lines: enumerate dirs + MountVPK per `_dir.vpk`).

## Launcher mod catalog

Two sections, one engine:

| | TC mods | Regular mods |
|---|---|---|
| Scan | `srceng/*/` containing `gameinfo.txt` (excl. `hl2`, `platform`) | `srceng/hl2/custom/` |
| Launch | `-game <dir>` | `-game hl2` |
| Class shown | from tier2 classifier | from tier2 classifier |
| Enable/disable | launcher list selection | move into/out of `custom/disabled/` (v1; v2: `addons.json` manifest) |
| Saves | `<tc>/save/` | `hl2/save/` |

Per-entry metadata: title (gameinfo `game`/`title`), class badge, icon
(extract `resource/` logo or `materials/vgui/...` VTF → PNG cache), disk size,
and the **honest warnings** from the classifier (DLL_ONLY: "custom NPCs/
weapons won't run"; STANDALONE_TC awaiting extraction: "run converter first").
Import on-device runs the same tier2 classification (C++ lib compiled into the
launcher) so phone-imported mods get the identical class + warnings as PC
conversions.

## Path mapping rules (converter + engine, must stay identical)

| PC release says | We store/emit |
|---|---|
| `C:\Program Files\Steam\...\hl2` | dropped (line removed) |
| `\|all_source_engine_paths\|hl2` | `\|hl2\|` |
| `\|all_source_engine_paths\|platform` | `\|platform\|` |
| `\|gameinfo_path\|.` | the mod's own dir |
| `../hl2`, `..\hl2` | `\|hl2\|` |
| `SteamAppId 215/218/420/...` | `220` (we ARE HL2) |
| `bin/*.dll` | stripped (Tier 3 / Tier 1 rebuild path instead) |
| `_dir.vpk` + numbered chunks | mounted via the `_dir.vpk` entry |

Regular mods **never** get a generated gameinfo — they are pure content under
`custom/` (Valve custom semantics: dirs/vpks only). Only TCs get a generated
gameinfo from the mount planner.

## Incompatibility matrix (what we honestly expect to break)

| Case | Result on our engine | Handling |
|---|---|---|
| DLL_ONLY addon content | Maps/models/materials/sounds fine; custom entities, weapons, scripted sequences dead | Warning badge + error-report contract (pipeline spec); Tier 3 optional later |
| Mapbase-lineage mods as content-only | Content mounts, but maps may reference Mapbase-only entities → missing-entity spam or empty areas | Detect "mapbase" in gameinfo (classifier already does); warn "needs Tier 1"; most still *display* correctly |
| MMod-style HD packs | Content works; gameplay behavior = base HL2 code | Warn: "visual upgrade only" |
| Custom compiled shaders (`.fxc`-built vcs) | Never load (no Windows compile path) | Strip at import + warning; almost always part of DLL-only mods anyway |
| New-format particles /pcf | Mostly tolerant; rare version mismatch | Validate at import, list failures |
| BSP 19–21, MDL 46–49, VTF 7.5 | Supported by the nillerusr engine baseline | None needed |
| `.gma` (Gmod workshop packs) | Gmod-only format, not HL2 content | Rejected at import with explanation |
| PC saves | Save format drift across engine versions | Per-mod save backup by launcher; import does not touch saves |
| Non-ASCII / `#`-heavy mod dir names | Scoped-storage + SAF path quirks | Normalize dir name at import; keep original title in manifest |
| Mods assuming `|gameinfo_path|`-relative `bin/` | Our bin layout is per-TC | Mount planner emits engine-default bin path; Tier 1 builds placed per mod |

## Explicitly out of scope (decided)

- Workshop integration (no Steam net hooks in the launcher).
- Per-mod standalone APKs (rejected: one engine-as-launcher APK).
- GoldSrc/Source 2 content (unchanged from research notes).

## Sources

- GDroid install guide: `/sdcard/srceng/garrysmod/addons/` local addon placement — github.com/tinsarfal/GDroid
- TF2/CSGO custom folder override semantics ("Each VPK and subfolder is added as a search path... files override the default game's files") — Valve developer wiki, gameinfo.txt / custom folder pages
- Existing project findings in `research-notes.md` (nillerusr engine, GDroid technique, WinlatorMali donors)
