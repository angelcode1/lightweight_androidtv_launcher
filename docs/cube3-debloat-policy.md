# Fire TV Cube 3 debloat compatibility notes

These package decisions are based on the tested AFTGAZL / Fire OS 7 configuration used with Gazelle Launcher.

## Screensaver stack

Keep:

    com.amazon.ftv.screensaver
    com.amazon.tv.localgallery

The active stock scenic screensaver service is:

    com.amazon.ftv.screensaver/.app.services.ScreensaverService

Safe to leave disabled for this use case:

    com.amazon.bueller.photos

The scenic Amazon Collection continued downloading/caching successfully after the current debloat set with the first two packages enabled and Amazon Photos disabled.

Do not add the screensaver or local-gallery packages to future bulk-disable lists unless the stock idle screensaver is intentionally being replaced.

## Separation of responsibilities

HOME wallpaper:

    Gazelle Launcher
      -> solid / Bing / Amazon Fire TV Collection / Wallhaven / custom HTTPS

Idle screensaver:

    com.amazon.ftv.screensaver
      -> existing Fire TV DreamService

Gazelle Launcher does not read or depend on Amazon's private app cache, database, or /data/user/0 files.

## Amazon Collection provider

The launcher runtime-fetches the Australian collection manifest from:

    https://d21m0ezw6fosyw.cloudfront.net/manifest/collections_en_AU_v3.json

Images are fetched from the same HTTPS CloudFront host. JPEG paths only are accepted.

The photographs are NOT bundled into the APK. Public CDN accessibility is not treated as a redistribution license. Before exposing the provider in a public release, review the applicable Amazon/content-owner terms.

The launcher keeps its own current image cache and a cached manifest fallback. The stock Amazon screensaver can therefore remain installed independently.
