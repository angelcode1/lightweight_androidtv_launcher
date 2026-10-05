# Gazelle Launcher

Fire TV Cube 3 (AFTGAZL / gazelle) optimized branch.

## What changed

- Removed Accessibility Home interception.
- Removed stock-launcher RAM killer.
- Removed WorkManager and the self-updater.
- Removed AndroidX runtime dependencies.
- Replaced RecyclerView/ConstraintLayout/AppCompat with Android framework UI.
- Shows both Android TV and normal phone/tablet launcher activities.
- Stores explicit component names, with package fallback if an app update renames the activity.
- Uses a 6 x 3 Home grid: up to 17 apps plus the Add tile.
- Releases icon and wallpaper bitmaps when the launcher is hidden.
- Keeps changing wallpapers without a resident worker or service.

## Build

    ./gradlew clean test lint assembleDebug

Release builds are unsigned unless all four signing variables are provided:

    SIGNING_STORE_FILE
    SIGNING_STORE_PASSWORD
    SIGNING_KEY_ALIAS
    SIGNING_KEY_PASSWORD

Do not install a temporary debug/CI-signed APK if the device is intended to keep launcher configuration across upgrades. Create and use the permanent Gazelle signing key first.

## Wallpaper design

Sources currently implemented:

- solid: no wallpaper bitmap.
- bing: Bing homepage image collection, Australian market.
- nature: Wallhaven safe nature search.
- custom: direct HTTPS image.

The changing-wallpaper mechanism itself is derived from upstream. Gazelle-specific hardening includes HTTPS-only image URLs, a 12 MB image cap, decode validation, and removal of the Picsum/Reddit fallback tiers.

Only one image is cached. A network check happens only when the launcher becomes visible and the configured interval has expired. Images are decoded using RGB_565.

Bing's HPImageArchive endpoint is not a documented public API and should be treated as replaceable. Google TV Ambient Mode, Amazon Ambient Experience and Roku Backdrops likewise do not expose documented third-party wallpaper-feed APIs suitable for a stable dependency.

## Home Assistant satellite IPC

The launcher exposes local Android broadcast actions protected by:

    com.gazelle.launcher.permission.CONTROL

The permission uses signature protection. The Gazelle satellite app should therefore be signed with the same certificate as the launcher.

Actions:

    com.gazelle.launcher.action.HOME
    com.gazelle.launcher.action.LAUNCH
    com.gazelle.launcher.action.WALLPAPER_REFRESH
    com.gazelle.launcher.action.WALLPAPER_SET_SOURCE

LAUNCH accepts:

    component
    package

If both are provided, component is tried first and package is used as fallback. If only package is provided, the launcher resolves the current launch activity for that package.

Commands may include:

    request_id
    reply_package

The receiver then returns:

    com.gazelle.launcher.action.RESULT

with:

    request_id
    command
    success
    message

Ordered broadcasts also receive RESULT_OK / RESULT_CANCELED and resultData.

WALLPAPER_SET_SOURCE accepts:

    source = solid | bing | nature | custom
    custom_url = optional HTTPS image URL

Recommended architecture:

    Home Assistant
       -> Wyoming / satellite command
       -> Gazelle satellite app
       -> explicit Android broadcast
       -> Gazelle Launcher

This avoids a listening TCP socket or permanent control service in the launcher.

## Platform scope

The current Cube 3 Fire OS 7 target is Android 9 / API 28, where this background receiver launch path is suitable. A future LineageOS port based on newer Android versions must re-evaluate background activity launch restrictions rather than assuming the same mechanism will work unchanged.

## Cube installation

After installing the APK, set its Home activity:

    cmd package set-home-activity com.gazelle.launcher/com.tvlauncher.MainActivity

Verify the Home handler before disabling any Amazon launcher component.

Do not disable the Amazon launcher until Home, reboot, sleep/wake, Settings, app launching and launcher-crash recovery have all been tested, and keep ADB plus a recovery/root path available.

## Measurement

No Cube-specific RAM or CPU claim is made yet. Measure the upstream launcher, Gazelle launcher, and Amazon launcher on the same AFTGAZL under the same conditions before drawing conclusions about PSS/USS/RSS or idle CPU.
