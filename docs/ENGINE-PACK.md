# The engine pack — ONE APK, engine inside

The launcher APK and the engine ship together. Nothing is delegated to a
separate app.

## What is bundled (v0.5.0+)

The engine is the **modern nillerusr-lineage build v1.17.0025** (December 2023,
aarch64, togl/GLES3 renderer, `liblauncher.so` entry point) — the same engine
generation AruMoon's "Source Engine for Android" app distributes. It replaces
the old 32-bit debug port (build 78, 2021) that hung during startup on
Android 14 devices and was ANR-killed (see the v0.4.2 release notes).

The pinned binary is fetched reproducibly by
`scripts/make-launcher-apk.sh --with-engine` from this repo's own release:

```
https://github.com/Kesurage/half-sourceG100/releases/download/engine-pack/srceng-engine-v1.17.0025.apk
sha256: 6b82a5c93753650dd2f22065a4040c722256bf9570f6f64c6dca3841db2899bb
```

Its signing certificate is bit-identical to nillerusr's public
`debug.keystore` (SHA-1 `80:A0:6A:09:C8:D6:FC:CE:D6:23:CB:06:E5:8C:DF:E5:24:E8:00:0D`)
— the ecosystem's standard autobuild key, verified before bundling.

Pieces merged into our APK:

| Piece | Notes |
|---|---|
| `lib/arm64-v8a/*.so` (28) | engine, materialsystem, vphysics, GameUI, SDL2, togl (GL translation), shaders, server browser, `libclient.so`/`libserver.so` (HL2 game code), `liblauncher.so` (entry) |
| `assets/extras_dir.vpk` + fonts | touch UI VPK — extracted to `filesDir` by `ExtractAssets` on first run; fonts used by vgui |
| *(not the dex)* | the Java glue is **compiled from source** — see below |

### Why the glue is compiled from source, not d8-merged

The v0.4.x builds d8-merged the old engine's `classes.dex`. That works only
until an engine class touches an `R` resource: `R.id.*` values are inlined
into the engine dex at *their* compile time, against *their* resource table —
in our APK those IDs resolve to nothing and the class crashes
(`Resources$NotFoundException`). The v1.17 engine's SDLActivity shows
permission/gameinfo dialogs from R strings, so a dex-merge would be a
ticking bomb.

Instead, `android-app/engine-src/` carries the engine's Java glue (from
[nillerusr/srceng-android](https://github.com/nillerusr/srceng-android),
`android-fixes` lineage — the exact source that produced the shipped dex):
SDLActivity, SDL stack, ValveActivity2, LauncherActivity, DirchActivity,
ExtractAssets, UpdateService/UpdateSystem. It is compiled against OUR
resources (`import com.z.halfsourceg100.R`), so every R reference is correct.
Local, minimal, documented patches:

1. `R` import rewritten to our package.
2. **Android 11–14 permission gate** (SDLActivity): `WRITE_EXTERNAL_STORAGE`
   is dead on API 30+ (auto-denied for targetSdk 34 — the old gate would have
   made the engine silently `finish()` right after start). Storage is
   guaranteed by the launcher preflight via MANAGE_EXTERNAL_STORAGE;
   `RECORD_AUDIO` (voice) is optional — denying the mic no longer blocks the
   game.
3. Icon drawables renamed `ic_launcher` → `srceng_icon` (no clash with ours).

## The startup contract (from srceng-android source, verified against the dex)

```
MainActivity (ours)
  -> EngineHost.launch()
       SharedPreferences "mod":
         gamepath = /sdcard/srceng        (VALVE_GAME_PATH)
         argv     = -console -condebug -novid [+ user args]
         env      = KEY=VALUE (optional)
       -> G100Bridge.prepare()            (LauncherActivity.PKG_NAME)
       -> ExtractAssets.extractVPK()      (assets/extras_dir.vpk -> filesDir)
       -> startActivity(org.libsdl.app.SDLActivity)
            extras: gamedir=<dir>   (hl2 | episodic | ep2 | <tc>)
                    gamelibdir=<nativeLibraryDir>  (bundled HL2 client/server)
                    vpk=<filesDir>/extras_dir.vpk
                    argv=<...>
            -> SDLActivity.init()
                 loads SDL2 + launcher (liblauncher.so, LauncherMainAndroid)
                 -> ValveActivity2.preInit()   (gameinfo.txt must exist)
                 -> ValveActivity2.initNatives()
                      setenv APP_LIB_PATH / APP_DATA_PATH / VALVE_GAME_PATH
                      setenv APP_MOD_LIB / EXTRAS_VPK_PATH / LANG
                      argv = "-game <gamedir> " + argv
                 -> SDLMain thread -> engine
```

Base games launch by directory name (`-game hl2|episodic|ep2`). TC mods pass
`-game <tcdir>`; a content-only TC runs on the bundled HL2 code (Tier 2).
Regular mods ride `hl2/custom/` (engine auto-mounts).

## Requirements / constraints

- **64-bit (arm64-v8a) device required.** The bundled engine set is aarch64
  only; the launcher checks `Build.SUPPORTED_ABIS` and refuses with a clear
  message otherwise. (The Helio G100 is arm64; its 64-bit userspace also
  means more addressable memory for the engine than the old 32-bit port.)
- **GLES 3.0** minimum (togl) — Mali-G57 MC2 provides GLES 3.2.
- Content: the user's legally-owned HL2 files go in `/sdcard/srceng/hl2`.

## Known constraint: argv tokenization (fixed in the fork, not yet in the pinned build)

The pinned v1.17.0025 `liblauncher.so` splits the Java argv string with
`strtok(argv, " ")` — **no quoting** — so a `-game` value containing a space
dies with a fatal missing-gameinfo Error (every TC mod with a spaced folder
name; base games never have one). The launcher works around it with the
argv-safe rename (see `docs/mount-architecture.md`, "Launch-time TC first
aid"). The fork carries the proper fix — a quote-aware tokenizer plus argv
bounds and NUL-termination (Kesurage/source-engine@56eeb46, verified on host:
quoted spans survive, plain names unchanged) — which activates when the next
engine pack is rebuilt from the fork. Until then the launcher must NOT pass
quoted arguments: the pinned tokenizer would treat the quotes as filename
characters.

## Updating the engine pack

When a newer engine build should be bundled (e.g. a future nillerusr
master autobuild):

1. Download the new universal `com.valvesoftware.source` APK
   (AruMoon's page / nillerusr autobuilds).
2. Verify its signature against nillerusr's public debug keystore, record
   its sha256.
3. Upload it as an asset to this repo's `engine-pack` release
   (`srceng-engine-<version>.apk`), keep older assets in place.
4. Update `ENGINE_URL` + `ENGINE_SHA256` in `scripts/make-launcher-apk.sh`
   and the version in this doc. Re-tag `v*` — CI rebuilds everything.
5. If the engine's Java glue changed upstream, refresh
   `android-app/engine-src/` from the matching srceng-android commit and
   re-apply the three patches above.

## Credits

The engine is nillerusr's Android port of the 2017 Source engine
(github.com/nillerusr/source-engine; the v1.17.0025 build is distributed via
AruMoon's "Source Engine for Android" page). Half-Life 2 and all game content
remain Valve's copyright; nothing of Valve's is redistributed by this
repository — the build script fetches the pinned engine binary from this
repo's own release at build time, and game content must be supplied by the
user.
