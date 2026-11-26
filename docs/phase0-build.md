# Phase 0 — build and packaging

How to go from clean Linux host to an installable Phase 0 APK.

## 1. One-time setup

```sh
git clone https://github.com/Kesurage/half-sourceG100.git
cd half-sourceG100
scripts/setup-build-env.sh            # NDK r10e + clang 11 + engine clone (into build-env/)
```

Also install Android SDK **build-tools** (for `aapt`, `d8`, `zipalign`, `apksigner`)
and a **platform android.jar** (API 21+), then:

```sh
export ANDROID_BUILD_TOOLS=/path/to/sdk/build-tools/34.0.0
export ANDROID_PLATFORM_JAR=/path/to/sdk/platforms/android-34/android.jar
```

## 2. Build the engine (aarch64 + GLES)

```sh
. build-env/env.sh
scripts/build-engine-aarch64.sh build-env
```

What it runs, decoded from the engine's `scripts/waifulib/xcompile.py`:

- `./waf configure -T release --android=aarch64,host,21 --togles --disable-warns -8`
- `--android=<arch>,<toolchain>,<api>` → `aarch64`, `host` (system clang with
  `--target=aarch64-linux-android21`), API 21
- `-8` = 64-bit; `--togles` = the GLES renderer
- On Android the build makes the `.so` set only — `launcher_main` is skipped
  (`DEST_OS == 'android'` early-return in its wscript), which is why the APK
  shell in `android-app/` is ours, not the engine's.

## 3. Package the APK

```sh
scripts/make-apk.sh build-env /path/to/your/hl2-content
adb install -r build-env/half-sourceG100.apk
```

## 4. Put HL2 content on the device

From your own Steam copy (PC side, DepotDownloader or steam depot extract):

```sh
adb shell mkdir -p /sdcard/Android/data/com.z.halfsourceg100/files/srceng
adb push hl2      /sdcard/Android/data/com.z.halfsourceg100/files/srceng/
adb push platform /sdcard/Android/data/com.z.halfsourceg100/files/srceng/
```

Expected layout (GDroid-compatible "srceng" layout):

```
srceng/
  hl2/         # hl2 game content
  platform/    # platform content
  bin/         # engine .so set also mirrored here at runtime
```

## Phase 0 gate (issue #1)

- [ ] `waf build` completes for aarch64
- [ ] APK installs, launches, creates GLES 3.0 surface at panel native resolution
- [ ] Engine boots to HL2 main menu from the srceng layout
- [ ] `d1_trainstation_01` loads: 15+ fps, low settings, no missing textures

## Known constraints (honest list)

- `System.loadLibrary` order in `EngineJni.java` is a first guess; the real
  dependency order comes from the engine's build output and will be fixed
  the first time it runs on device.
- NDK r10e is ancient — that's deliberate (known-good for this engine). Don't
  modernize until the baseline works (see roadmap risk table).
- Content is not packaged inside the APK on purpose: AGL asset limits (2 GB
  total, 500 MB per entry) can't hold HL2 VPKs. Sideload path only for now;
  the Phase 3 launcher gets a proper downloader (Valve-on-android approach).
