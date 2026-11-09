[中文](README.md)
|
English

# Fcitx Android Evolve

A pinyin keyboard for Android built on [Fcitx5](https://github.com/fcitx/fcitx5),
focused on the hand-typed input experience in the AI era: getting the typing feel right.

It is a fork of [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android).

> Status: personal project, in daily use but not released. There is no prebuilt
> APK yet; build it yourself (see below).

## What's different from fcitx5-android

### Look
- Two built-in themes (light / dark): borderless keys, 2 dp corners, teal return key
- Retuned keyboard height: a fixed height per screen size by default, or a
  percentage of the screen height, with a 90–110% fine adjustment
- Keys drawn at the top of their cell with the vertical gap below; the toolbar
  always shows, with a 中/英 switch at its start

### Typing feel
- Touch-aware correction: a 2D Gaussian key model scores the keys around each
  touch and the pinyin engine tries the likely neighbors; typos into the row
  above or below are corrected as readily as left or right ones
- Tuned touch handling: hysteresis when sliding between keys, rolling presses
  with several fingers committed in order, shift that capitalizes while held
  and locks on double tap, space bar cursor scrubbing, backspace repeat, key
  previews kept 100 ms after release
- A tap in the padding above the keys selects the candidate above it
- Vibration on and key sound off by default

### Pinyin and dictionaries
- Better dictionary import
- More efficient ranking by typed code
- Much better mixed Chinese and English input

### Symbols and emoji
- Symbol keyboard with categories
- Emoji panel with recents and categories, and emoji search in Chinese or
  English (猫 / cat)

### Settings
- The globe key only toggles Chinese / English; long press space to pick an
  input method
- LAN clipboard sync (HTTP port 51423, UDP discovery and pairing)

## Installing

The package name is still `org.fcitx.fcitx5.android`, the same as the official
app but signed differently: uninstall the official app first. Their data is
not shared.

## Build

### Dependencies

- Android SDK Platform & Build-Tools 35
- Android NDK (Side by side) 25 & CMake 3.22.1, from Android Studio's SDK Manager
- [KDE/extra-cmake-modules](https://github.com/KDE/extra-cmake-modules)
- GNU Gettext >= 0.20 (for `msgfmt`)

Recommended versions are in [Versions.kt](build-logic/convention/src/main/kotlin/Versions.kt).

### Steps

```shell
git clone https://github.com/Yumeri0913/Fcitx-Android-Evolve.git && cd Fcitx-Android-Evolve
git submodule update --init --recursive

# macOS
brew install extra-cmake-modules gettext
# Debian/Ubuntu
sudo apt install extra-cmake-modules gettext

./gradlew :app:assembleRelease
```

Changes to libime and fcitx5-chinese-addons live as patches in
[patches](patches); `patches/ApplyPatches.cmake` applies them when CMake
configures, nothing to do by hand.

For Windows, Nix and Android Studio troubleshooting, see
[fcitx5-android's README](https://github.com/fcitx5-android/fcitx5-android#build).

## Test

```shell
./gradlew :app:testDebugUnitTest          # unit tests
./gradlew :app:connectedDebugAndroidTest  # instrumented tests, needs a device or emulator
```

## Upstream

Generic fixes are welcome upstream at
[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android). Thanks to
its authors and to the [Fcitx](https://github.com/fcitx) project.

## License

[LGPL-2.1](LICENSE).
