# Tier 2 implementation map

Where each piece of the mod-compatibility layer (spec: `mod-compatibility-pipeline.md`) lives, and how to run its tests.

## Layout

```
tier2/                    C++ library — compiles into the engine / launcher
  include/hsg100/
    keyvalues.hpp         KeyValues1 parser (gameinfo.txt / vdf)
    modclassifier.hpp     mod classes + ModInfo
    vpk.hpp               VPK v1/v2 directory reader
    mountplanner.hpp      generated gameinfo / search-path planner
  src/                    implementations (keyvalues, modclassifier, vpk, mountplanner)
  tests/test_tier2.cpp    full unit-test suite (no framework)
tools/
  tc_extractor.py         PC/CI-side extraction pipeline (classifier + converter twins)
tests/
  test_tc_extractor.py    unittest suite for the extractor
```

The C++ side is the engine-integrated truth; the Python side exists so
conversions can run on a PC or CI without a compiler. Both implement the same
classification contract — keep their behavior in sync when either changes.

## Mod classes

| Class | Meaning | Path |
|---|---|---|
| `SOURCE_MOD` | `src/` tree present | Tier 1 native build, then full play |
| `CONTENT_ONLY` | content dirs / VPKs, no code | Tier 2 direct mount |
| `DLL_ONLY` | closed Windows `client.dll`/`server.dll` | Tier 2 content + base code fallback; Tier 3 optional |
| `STANDALONE_TC` | ships its own engine (`hl2.exe`, `bin/engine.dll`, …) | run extractor, then re-classify |
| `EMPTY` / `BROKEN_GAMEINFO` | nothing usable | honest error |

## Running the tests

```sh
cd tier2 && make test          # C++ suite — expects "ALL PASS"
python3 tests/test_tc_extractor.py   # Python suite — expects 14/14 OK
```

## Extraction contract (what the extractor does to a TC)

- KEEP: content dirs (`maps materials models sound resource scripts particles cfg media scenes expressions hl2 platform actions save`), VPKs (signature-validated), `gameinfo.txt` (as `.orig`)
- STRIP: `*.exe`/`*.dll`/`bin/`, redists (`directx`, `vc_redist`, …), installers, launchers
- FIX: new `gameinfo.txt` — `SteamAppId` re-parented to 220 (HL2), mod content mounted with highest priority, base HL2 + platform appended last (the anti-texture-error fallback). Rev 3: only engine-valid search-path tokens, VPKs listed explicitly without the `_dir` suffix, numbered chunks never listed, earlier rows win (2013 branch)
- REPORT: `manifest.json` (class, kept/stripped lists, VPK versions, warnings) so conversions are reproducible and shareable

## Notable implementation details

- VPK entry layout (VDC): `crc u32 | preload u16 | archiveIndex u16 | offset u32 | length u32 | terminator u16 | preload[]`; v2 adds a 12-byte extended header before the tree.
- KeyValues parser accepts quoted strings with `\"`/`\\` escapes, bare tokens, `//` and `/* */` comments; unbalanced braces are a parse error (that's how `BROKEN_GAMEINFO` is detected).
- Mount planner rev 3 emits only tokens the engine expands (`|gameinfo_path|`, `|all_source_engine_paths|`). Rev 2's `|moddir|`/`|hl2|`/`|platform|` forms are literal directory names to the engine and mounted NOTHING — regression-tested in `tier2/tests/test_tier2.cpp` and `tests/test_tc_extractor.py`.
- The classifier's game-dir discovery: `gameinfo.txt` at root or exactly one level below — matches how TC installers lay out releases.
