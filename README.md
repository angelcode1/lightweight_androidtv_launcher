# Gazelle Launcher — Fire TV Cube 3

A small Android framework-based home launcher for the **Fire TV Cube 3 (AFTGAZL, `gazelle`)**. The Gazelle feature branch has been merged into `main`; this fork is not the upstream Wiiguy launcher release.

## Download

Download from **[this fork's Releases](https://github.com/angelcode1/lightweight_androidtv_launcher/releases)**. Select the newest Gazelle prerelease and download:

- `gazelle-launcher-*-release-signed.apk` — normal APK installer.
- `gazelle-launcher-privileged-v*.zip` — Magisk module variant for privileged Fire OS launcher permissions; use on a rooted Cube only.
- `APK-CERTIFICATE.txt`, `SHA256SUMS.txt`, `SIGNING-MODE.txt` — verification and signing metadata.

**Important signing limitation:** releases made without permanent keystore secrets use a new temporary signing key each run. Those APKs **cannot upgrade each other in place**, even with a higher version code. You must uninstall the older different-key package first, which may erase app data. The earlier `v1.6.1-gazelle.13` release used a temporary key. Only a consistent permanent signing keystore permits normal future in-place upgrades.

## Install on Cube 3

Install the downloaded APK using ADB, then select Gazelle as HOME:

```bash
adb install -r gazelle-launcher-1.6.1-gazelle.14-release-signed.apk
adb shell cmd package set-home-activity com.gazelle.launcher/com.tvlauncher.MainActivity
```

If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the existing package has a different signing certificate. Back up launcher preferences first if necessary; uninstall the old launcher only when you are ready to lose its data. Keep ADB and a working recovery/Home fallback until reboot, sleep/wake, Settings and app launching have all been tested.

For privileged installation using Magisk, install the matching module ZIP and reboot. On-device verification:

```bash
adb shell dumpsys package com.gazelle.launcher | grep -E 'SYSTEM|PRIVILEGED|LAUNCHER_SETTINGS'
```

See [GAZELLE.md](GAZELLE.md) for additional installation, permission, Home Assistant IPC, and Fire OS safety details.

## Features

- Framework-only HOME carousel for up to 17 pinned apps plus the Add tile; roughly seven visible at once.
- Supports Android TV and ordinary launcher activities, with app pin repair after app updates.
- Optional solid, Bing AU, Amazon Fire TV Collection AU, nature, and custom HTTPS wallpaper modes.
- Wallpaper refresh without a permanently resident background service.
- Home Assistant satellite control through signature-protected Android broadcasts.
- Removes upstream Accessibility HOME interception, stock launcher RAM killer, WorkManager, self-updater, and AndroidX UI runtime dependencies.
- Keeps icons warm and asynchronously refreshes HOME apps/wallpaper for efficient UI return.

## Build locally

Requires JDK 17, Android SDK API 34 and Gradle 8.5 (checked-in Gradle wrapper).

```bash
./gradlew test lint assembleDebug assembleRelease
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk` — debug signed for local testing.
- `app/build/outputs/apk/release/app-release-unsigned.apk` — unsigned release verification.
- `app/build/outputs/apk/release/app-release.apk` — signed release only when `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD` are configured.

There are no `github` or `fdroid` product flavors in this Gazelle fork.

## GitHub Actions

- [Gazelle CI](.github/workflows/ci.yml): on pushes to `main` and PRs; runs tests, lint and debug/release builds, and uploads the installable debug APK and unsigned release APK as temporary workflow artifacts.
- [Unsigned verification](.github/workflows/release.yml): manual build artifact without creating a tag or release.
- [Signed publication](.github/workflows/signed-release.yml): on `main` version updates in `app/build.gradle`, explicit `.github/release-trigger` changes, or manual dispatch. Verifies APK package/version/signature and publishes an immutable tagged prerelease plus a Magisk module ZIP.

A new release **must** increment both `versionName` and `versionCode` in `app/build.gradle`; tags cannot be reused. GitHub Actions signs using four optional repository secrets:

```text
GAZELLE_SIGNING_KEYSTORE_B64
GAZELLE_SIGNING_STORE_PASSWORD
GAZELLE_SIGNING_KEY_ALIAS
GAZELLE_SIGNING_KEY_PASSWORD
```

When all are present, the permanent key is used; when none are present, the workflow creates an explicitly **temporary-key test prerelease** with the upgrade limitation above. Partial secrets cause a hard failure.

## Screenshots

![Gazelle home screenshot](fastlane/metadata/android/en-US/images/phoneScreenshots/01_home_screen.png)

![App selection](fastlane/metadata/android/en-US/images/phoneScreenshots/02_app_selection.png)

## Technical documentation

See [GAZELLE.md](GAZELLE.md) and the [docs directory](docs) for detailed implementation, satellite permissions, wallpaper security, and debloat compatibility.

Licensed under the repository's [LICENSE](LICENSE).
