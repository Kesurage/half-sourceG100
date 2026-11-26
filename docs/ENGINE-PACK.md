# The engine pack — ONE APK, engine inside

v0.4.0 replaces the "launcher shell + external engine app" split with a single
APK: the mod manager AND the engine ship together. Nothing is delegated to a
separate app anymore.

## What is bundled

The engine is the pinned prebuilt **nillerusr old-port build** (NVIDIA
Shield-port lineage, build 78, 2021), fetched reproducibly by
`scripts/make-launcher-apk.sh --with-engine` from:

```
https://raw.githubusercontent.com/nillerusr/just-old-shit/srceng-old-port/srceng-debug.apk
```

Contents merged into our APK:

| Piece | Notes |
|---|---|
| `lib/armeabi-v7a/*.so` (35) | engine, materialsystem, vphysics, GameUI, SDL2, Regal GL wrapper, game code (hl2 / episodic / portal client+server) |
| `assets/{hl2,episodic,ep2,portal,platform}` | touch UI materials, shader caches, configs — mounted by the engine via AAssetManager |
| `classes.dex` | SDLActivity (patched), ValveActivity2 glue, Games table — d8-merged with our launcher classes |

## The startup contract (recovered from the shipped dex)

```
MainActivity (ours)
  -> EngineHost.launch()
       writes SharedPreferences "mod":
         gamepath   = /sdcard/srceng          (content root)
         argv       = <user args> [-game <tc>]
         env        = KEY=VALUE (LIBGL_USEVBO=0 default)
         game       = 0..3  (hl2 / episodic / ep2 / portal)
         show_touch, resolution_*, fixed_resolution=false, immersive_mode
       -> me.nillerusr.G100Bridge.prepare()   (engine-side statics)
       -> startActivity(org.libsdl.app.SDLActivity)
            -> SDLActivity.onCreate()
                 -> ValveActivity2.initNatives()   [engine callback]
                      setGame / setArgs / setEnvs / setDocumentDirectoryPath
                      LIBRARY_SERVER / LIBRARY_CLIENT (base-game code libs)
                 -> SDLMain.run() -> nativeInit() -> libengine.so
```

Base games go through the official table index (guaranteed). TC mods pass
`-game <dir>` on argv — the engine carries the standard Source cmdline parser
and `gameinfo.txt` loading — plus the closest base-game index from the TC's
lineage as the fallback. Regular mods ride `hl2/custom/` (engine auto-mount).

## Requirements / constraints

- **32-bit support required**: the old-port engine is armeabi-v7a only. The
  Helio G100 (Cortex-A75/A55) runs 32-bit apps; the launcher checks
  `Build.SUPPORTED_ABIS` and refuses engine launch with a clear message if the
  device lacks it.
- **GLES 2.0** minimum (Regal path) — any Android 7+ device has it.
- Content: the user's legally-owned HL2 files go in `/sdcard/srceng/hl2`
  (see docs/launcher.md).

## Upgrade path (Phase 3, M2)

The old-port engine is the bridge to a working product today. The Phase-2/3
engine work (aarch64, togles GLES3, dxvk-native/Vulkan — see
docs/PHASE3-VULKAN.md) is built from source by CI
(`scripts/build-engine-aarch64.sh`) and will replace this bundle inside the
SAME launcher APK — the mod manager, mount architecture and launch contract
stay identical.

## Credits

The engine is nillerusr's Android port of the 2017 Source engine
(github.com/nillerusr/source-engine, srceng-old-port lineage, itself based on
the NVIDIA Shield ports). Half-Life 2 and all game content remain Valve's
copyright; nothing of Valve's is redistributed by this repository — the build
script fetches the pinned engine binary from nillerusr's own repo at build
time, and game content must be supplied by the user.
