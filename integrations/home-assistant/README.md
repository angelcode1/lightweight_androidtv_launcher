# Nabu → Home Assistant → Gazelle Launcher (voice-only bridge)

This is a **Home Assistant configuration**, not an APK or an Alexa replacement.

The existing Cube's Nabu microphone/PTT sends speech to Home Assistant Assist through Wyoming. Home Assistant matches the voice sentence and sends HOME or an app-launch action to the Cube via the **Android Debug Bridge** integration. Nabu then speaks the normal Assist response. No changes to the working Nabu audio path.

No new resident launcher service, dashboard, browser, token, or APK build. Kodi-specific logic is intentionally deferred. Gazelle's signature-protected ControlReceiver is unchanged.

## Setup

1. Configure Home Assistant's **Android Debug Bridge** integration for the Cube under **Settings → Devices & services**, and approve its ADB connection. This is not the Android TV Remote integration, which is incompatible with Fire TV. Some Fire TV devices accept only one ADB client at a time, so disconnect Mac ADB while testing Home Assistant if needed.
2. In Developer Tools → States, identify the **Android Debug Bridge** media-player entity ID. Replace **both** instances of `media_player.fire_tv_cube` in `packages/nabu_gazelle.yaml` with that actual ID.
3. Copy `packages/nabu_gazelle.yaml` to your Home Assistant config directory at `packages/nabu_gazelle.yaml`. If your Home Assistant installation doesn't already load packages, merge this into the existing `homeassistant:` block in `configuration.yaml` (do not create a second block):

   ```yaml
   homeassistant:
     packages: !include_dir_named packages
   ```

4. Copy `custom_sentences/en/gazelle.yaml` into your Home Assistant config directory at `custom_sentences/en/gazelle.yaml`.
5. Validate configuration; restart/reload HA configuration as necessary so custom sentences and intent scripts are loaded.

## Test

In Home Assistant Developer Tools → Actions, run:

```yaml
action: conversation.process
data:
  text: "open Gazelle"
```

Verify that Gazelle becomes HOME. Then try `open Plex` or `open SmartTube`. Once the text action works, speak the same phrase through Nabu on the Cube. The text-only Developer Tools action does not automatically play the spoken response; the live Nabu Assist pipeline does that.

## Security and scope

- Only installed package IDs from the explicit `gazelle_app` allowlist are passed to `media_player.select_source`.
- Do **not** use arbitrary spoken text as a shell command, broadcast component, or package ID.
- To extend, add verified installed app names/IDs to the list. Kodi playback/search integration can follow separately, using the native Kodi integration or Android media keys.
- These sentences target Home Assistant's **built-in** Assist recognizer. An LLM-backed conversation agent may need a separately exposed script/tool.

## Troubleshooting

- `androidtv.adb_command` missing: configure the **Android Debug Bridge** integration, not Android TV Remote.
- HOME has no effect: verify the correct HA media-player entity; test `androidtv.adb_command` with command `HOME` directly.
- App does not launch: test `media_player.select_source` using the package ID and inspect HA ADB logs.
- Sentence not recognized: verify the `custom_sentences/en/gazelle.yaml` location and reload/restart HA.

No Vizzini reinstall, Alexa VSK permission spoofing, Magisk changes, protected receiver weakening, or Nabu restart is part of this bridge.
