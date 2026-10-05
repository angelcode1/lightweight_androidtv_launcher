# Gazelle Launcher

Fire TV Cube 3 (AFTGAZL / gazelle) optimized branch.

## What changed

- Removed Accessibility Home interception.
- Removed stock-launcher RAM killer.
- Removed WorkManager and the self-updater.
- Removed AndroidX runtime dependencies.
- Replaced RecyclerView/ConstraintLayout/AppCompat with Android framework UI.
- Shows both Android TV and normal phone/tablet launcher activities.
- Stores explicit component names, with direct package fallback if an app update renames the activity.
- Uses a responsive 6 x 3 Home grid: up to 17 apps plus the Add tile.
- Keeps the small icon cache warm across ordinary app launches while releasing the large wallpaper bitmap when HOME stops.
- Keeps changing wallpapers without a resident process; HA-triggered wallpaper work is handed to a short-lived JobService.

## Build

    ./gradlew clean test lint assembleDebug

Release builds are unsigned unless all four signing variables are provided:

    SIGNING_STORE_FILE
    SIGNING_STORE_PASSWORD
    SIGNING_KEY_ALIAS
    SIGNING_KEY_PASSWORD

Create and use the permanent Gazelle signing key before the first device install if launcher configuration must survive upgrades.

## Wallpaper design

Sources currently implemented:

- solid: no wallpaper bitmap;
- bing: Bing homepage image collection, Australian market;
- nature: Wallhaven safe nature search;
- custom: direct HTTPS image.

Gazelle-specific hardening includes HTTPS-only image URLs, a 12 MB download cap, source dimension validation, an 8.3 MP source-pixel cap, a total refresh deadline, cache/source-key validation, and removal of the Picsum/Reddit fallback tiers.

Centre-crop decoding uses the largest power-of-two BitmapFactory sample that leaves both decoded dimensions at least as large as the display target. This avoids decode bombs without downsampling common 2560x1440 or 1920x1200 wallpapers and then forcing CENTER_CROP to upscale them.

Each refresh captures the source configuration at start. Different source configurations may refresh concurrently, but a stale refresh cannot replace the cache or advance LAST_FETCH after the source/custom URL changes. The cache stores its configuration key and is not displayed for another source.

Bing's HPImageArchive endpoint is not a documented public API and should be treated as replaceable. Google TV Ambient Mode, Amazon Ambient Experience and Roku Backdrops likewise do not expose documented third-party wallpaper-feed APIs suitable for a stable dependency.

## Pinned-app repair

Pinned entries are handled as follows:

- component still valid: show it;
- package installed and enabled but component renamed: resolve that package directly with getLeanbackLaunchIntentForPackage() then getLaunchIntentForPackage(), and repair the saved component;
- package installed but disabled: hide it temporarily and preserve the saved id;
- package uninstalled: remove the stale id;
- package installed/enabled but no longer launchable: remove the stale id.

Repair does not enumerate or load labels for every installed launcher activity.

## Home Assistant satellite IPC

The launcher exposes local Android broadcast actions protected by:

    com.gazelle.launcher.permission.CONTROL

The Gazelle satellite should use the same permanent signing certificate and declare the permission contract shown in:

    docs/satellite-control-manifest.xml

### HOME and LAUNCH: synchronous ordered-broadcast contract

Actions:

    com.gazelle.launcher.action.HOME
    com.gazelle.launcher.action.LAUNCH

If the caller needs a result, it MUST send an ordered broadcast.

These actions return only the ordered-broadcast result:

    RESULT_OK       + home_started
    RESULT_OK       + launch_started
    RESULT_CANCELED + home_start_failed
    RESULT_CANCELED + launch_failed

HOME and LAUNCH do not send com.gazelle.launcher.action.RESULT, and reply_package/request_id are not used for their completion result.

LAUNCH accepts:

    component
    package

When both are supplied, component is tried first and package is the fallback. With only package, the launcher uses getLeanbackLaunchIntentForPackage() then getLaunchIntentForPackage().

### Wallpaper commands: asynchronous contract

Actions:

    com.gazelle.launcher.action.WALLPAPER_REFRESH
    com.gazelle.launcher.action.WALLPAPER_SET_SOURCE

An ordered result of:

    RESULT_OK + accepted

means only that the asynchronous operation was accepted/queued. It does NOT mean the network operation completed successfully.

For the final result, the caller supplies:

    request_id
    reply_package

The launcher later sends:

    com.gazelle.launcher.action.RESULT

to reply_package with:

    request_id
    command
    success
    message

Possible final messages include:

    wallpaper_refreshed
    wallpaper_source_set
    wallpaper_refresh_failed
    wallpaper_source_fetch_failed
    already_in_progress
    superseded
    schedule_failed

WALLPAPER_SET_SOURCE accepts:

    source = solid | bing | nature | custom
    custom_url = optional HTTPS image URL

The solid source completes immediately but follows the same wallpaper API shape: the ordered broadcast returns accepted and, when reply_package is supplied, the final ACTION_RESULT reports wallpaper_source_set.

Recommended architecture:

    Home Assistant
       -> Wyoming / satellite command
       -> Gazelle satellite app
       -> explicit Android broadcast
       -> Gazelle Launcher

This avoids a listening TCP socket or permanent control service in the launcher.

## Platform scope

The current Cube 3 Fire OS 7 target is Android 9 / API 28, where this background receiver launch path is suitable. A future LineageOS port based on newer Android versions must re-evaluate background-activity-launch restrictions.

## Cube installation

After installing the APK, set its Home activity:

    cmd package set-home-activity com.gazelle.launcher/com.tvlauncher.MainActivity

Verify the Home handler before disabling any Amazon launcher component.

Do not disable the Amazon launcher until Home, reboot, sleep/wake, Settings, app launching and launcher-crash recovery have all been tested, and keep ADB plus a recovery/root path available.

## Measurement

No Cube-specific RAM or CPU claim is made yet. Measure the upstream launcher, Gazelle launcher and Amazon launcher on the same AFTGAZL under the same conditions before drawing conclusions about PSS/USS/RSS or idle CPU.

## Verification status

At commit af4fb8e, all Kotlin source files compiled against android-34.jar using a stub R, all R references resolved, and the five pure unit tests passed under a minimal JUnit shim. AAPT, lint, R8 and on-device behavior remained unverified.

Subsequent fixes changed sampling, pinned-app repair, wallpaper source-race handling and HOME lifecycle behavior, so those changes require the same compile/test pass again before the branch should be treated as build-verified.

Current pure tests cover:

- decode-bomb dimension rejection;
- centre-crop-safe power-of-two sample-size calculation;
- 1-hour interval index preservation;
- disabled-id preservation, uninstalled-id pruning and component repair.
