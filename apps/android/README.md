# Zeron for Android

A Jetpack Compose client of the Rust mobile core (`crates/mobile`), laid out to follow the iOS app: Rust decides what the transcript paints and where; Android draws that display list, scrolls it, and handles gestures.

Debug builds start in **demo mode** (the Rust `DemoHost`, the same offline workspace as the iOS `-demo` launch). No account and no network are required. Sign-in is still on the first-run screen if you sign out, and it returns to the demo from “Explore the demo”.

## One-command build

From the repo root, with the Android SDK and NDK installed:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"   # or %LOCALAPPDATA%\Android\Sdk on Windows
scripts/android/build-apk.sh
```

The script builds `libzeron_mobile.so` for **arm64-v8a** and **x86_64**, generates the UniFFI Kotlin bindings, applies the small Kotlin 2 compatibility patch, and runs `./gradlew :app:assembleDebug`.

The debug APK is:

```text
apps/android/app/build/outputs/apk/debug/app-debug.apk
```

Install it on an emulator or device:

```bash
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

x86_64 is the ABI the Windows Android Studio emulator uses. arm64-v8a is for devices and Apple Silicon emulators.

### What you need

- Rust stable (see `rust-toolchain.toml`) and targets `aarch64-linux-android`, `x86_64-linux-android`
- [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) (`cargo install cargo-ndk`)
- Android SDK: `platforms;android-35`, `build-tools;35.0.0`, platform-tools
- Android NDK r27 (`ndk;27.2.12479018` is what this tree was built with)
- JDK 17

`ANDROID_HOME` or `ANDROID_SDK_ROOT` must point at the SDK. The script finds the newest NDK under `$ANDROID_HOME/ndk` when `ANDROID_NDK_HOME` is unset.

### Windows

Use Git Bash or WSL so `scripts/android/build-apk.sh` can run. Point `ANDROID_HOME` at the SDK Android Studio installed, typically `%LOCALAPPDATA%\Android\Sdk`. Install the NDK from Android Studio’s SDK Manager (NDK side by side), then:

```bash
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"   # Git Bash
scripts/android/build-apk.sh
```

Open `apps/android` in Android Studio and run the `app` configuration on an x86_64 emulator if you would rather not use the script. The native library has to be produced first; `build-apk.sh` copies the stripped `.so` files into `app/src/main/jniLibs/` (that directory is gitignored).

### Deep links for screenshots

The activity reads intent extras, the same idea as the iOS `-route` argument:

```bash
adb shell am start -n sh.zeron.android/.MainActivity \
  --es route settings --es theme dark
```

`route` is `settings`, `search`, `new`, `spaces` (space-filter menu), `session` (with `--es chat <id>`), or `signin`. `theme` is `light`, `dark`, or `system`. A fresh install defaults to dark.

## Approximations

iOS uses system Liquid Glass. On API 31+ the Android capsules sample a `RenderEffect` blur of the content behind them (`GlassFrameLayout`), then add a tint and a hairline. Older devices get a translucent fill. The sessions list in demo mode sits on the same near-black backdrop as the iOS reference (`#0D0D0D`) until a wallpaper is chosen.

The front page matches the iOS sessions chrome: an “All” space filter, new-session and profile capsules, and no tab bar. Profile opens Settings; Search is a row in Settings. System back pops the session stack, the new-session sheet, sign-in, and Settings. The composer moves with the IME (`adjustResize` plus `imePadding`).
