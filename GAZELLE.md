# Gazelle Launcher

Fire TV Cube 3 (AFTGAZL / gazelle) optimized branch.

## What changed

- Removed Accessibility Home interception.
- Removed stock-launcher RAM killer.
- Removed WorkManager and the self-updater.
- Removed AndroidX runtime dependencies.
- Replaced RecyclerView/ConstraintLayout/AppCompat with Android framework UI.
- Shows both Android TV and normal phone/tablet launcher activities.
- Stores explicit component names, allowing multiple launchable activities from one package.
- Uses a 6 x 3 Home grid: up to 17 apps plus the Add tile.
- Releases icon and wallpaper bitmaps when the launcher is hidden.
- Keeps changing wallpapers without a resident worker or service.

## Build

    ./gradlew clean assembleDebug
    ./gradlew assembleRelease

## Wallpaper design

Sources currently implemented:

- solid: no wallpaper bitmap.
- bing: Bing homepage image collection, Australian market.
- nature: Wallhaven safe nature search.
- custom: direct HTTPS image.

Only one image is cached. A network check happens only when the launcher becomes visible and the configured interval has expired. Images are validated and decoded using RGB_565.

Google TV Ambient Mode, Amazon Ambient Experience and Roku Backdrops are useful reference experiences, but they do not provide a documented third-party wallpaper-feed API suitable for this launcher. They should not be scraped into the build.

## Home Assistant satellite IPC

The launcher exposes local Android broadcast actions protected by:

    com.gazelle.launcher.permission.CONTROL

The permission uses signature protection. The Gazelle satellite app should therefore be signed with the same certificate as the launcher.

Actions:

    com.gazelle.launcher.action.HOME
    com.gazelle.launcher.action.LAUNCH
    com.gazelle.launcher.action.WALLPAPER_REFRESH
    com.gazelle.launcher.action.WALLPAPER_SET_SOURCE

LAUNCH accepts one of these extras, in priority order:

    component
    package
    label

For label launching, matching is exact first, then prefix, then substring.

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

## Cube installation

After installing the APK, set its Home activity:

    cmd package set-home-activity com.gazelle.launcher/com.tvlauncher.MainActivity

Verify the Home handler before disabling any Amazon launcher component.

Do not disable the Amazon launcher until Home, reboot, sleep/wake, Settings, app launching and launcher-crash recovery have all been tested.
