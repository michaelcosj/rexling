# <img src="rexling-android/app/src/main/res/drawable-nodpi/rexlink_dinosaur.png" alt="" width="40" height="40" align="absmiddle"> Rexling (personal fork)

A personal fork of [shulhi/rexling](https://github.com/shulhi/rexling), the
unofficial native Android client for Superlogical Rex.
For the full feature guide, see the
[upstream README](https://github.com/shulhi/rexling#readme).

Rexling connects straight to your authenticated Rex server over Tailscale. The UI
is Kotlin/Compose, the Rex protocol is handled in Rust, and libghostty-vt keeps the
terminal state.

## What's different in this fork

- **Shortcut profiles.** Floating shortcut menus for any program, not just Neovim.
  A Shell profile appears at the prompt; lines starting with `!` ask before
  running. Edit them under **Preferences → Shortcuts**.
- **Rex-styled settings.** Preferences, menus and dialogs redesigned to match the
  terminal UI.
- **Terminal padding** options.
- **CI builds.** Signed release APKs built on GitHub Actions.

## Install

Requirements: a 64-bit ARM phone running Android 9 or newer, plus a Rex server you can
reach, normally over Tailscale.

Download the APK from this fork's [Releases](../../releases) and install it:

```sh
adb install -r Rexling-<version>-arm64-release.apk
```

Open Rexling, enter your Rex server's private HTTPS URL, and connect. To add more
hosts, go to **⋮ → Servers**.

## Build

You need Android Studio, Android SDK 37, NDK 30.0.16248370, Rust 1.93 or newer,
and Python 3.9 or newer. The full setup is in [BUILDING.md](BUILDING.md).

```sh
./rexling-android/scripts/gradle.sh :app:assembleRelease
```

## License

Apache License 2.0. Copyright 2026 Shulhi Sapli and contributors, with
modifications in this fork by Michael (michaelcosj). See [LICENSE](LICENSE),
[NOTICE](NOTICE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Rexling is an independent, unofficial client and is not an official Rex product.
It is provided as-is, without warranty.
