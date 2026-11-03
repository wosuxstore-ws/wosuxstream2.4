# Wosux Stream: free live TV for Android phones, tablets and Android TV boxes

Wosux Stream is a fast, Netflix-style player for live TV. It plays free, community-maintained **M3U playlists** of publicly
available streams (by default [iptv-org](https://github.com/iptv-org/iptv)); you can also load any M3U link of your own.
No ads, no accounts, no paid services. The app contains no channels itself.

Kotlin, View-based UI (XML + ViewBinding), AndroidX, Media3 (ExoPlayer), Coil, OkHttp. Works with a touch screen and with a TV remote (D-pad).

## Features
- **Home**: a hero banner, then rows of channels by category. **Continue watching** (your last channels) and **My List** (your favorites) come first. "See all >" opens a whole category as a grid.
- **Browse**: a grid of every channel with instant search (also inside your favorites).
- **My List**: your favorite channels, newest first.
- **Sources**: pick a country, category or language, or paste your own M3U link.
- **Player**: tune-in screen with the channel logo, an overlay with play / pause, previous / next and favorite, a channel list panel, swipe zapping on a phone, and a friendly error card with **Retry** and **Next channel** (it can jump to the next channel by itself).
- **Offline channels**: channels are tested in the background and the ones that do not work are dimmed. Zapping can skip them, and Home can hide them.
- **Settings**: hide offline channels, skip offline channels when zapping, check channels in the background, video quality (Auto / SD / 720p), channel source, refresh the playlist, clear the image cache, and the version.
- **Fast and offline-friendly**: the playlist and the channel logos are cached on the device, so Home opens quickly and still shows offline. The playlist is refreshed when it is older than 6 hours (or with "Refresh playlist now").
- **Crash report**: if the app ever crashed, it shows the saved report at the next start.

## Controls
| | Remote (D-pad) | Touch |
|---|---|---|
| Move around the lists | arrow keys | scroll |
| Open / play a channel | OK | tap |
| Add / remove a favorite (on a channel card) | hold OK | long-press |
| Add / remove a favorite (in the player) | heart button (OK) | heart button |
| Next / previous channel (in the player) | UP / DOWN or CH+ / CH- | swipe up / down on the video, or the Next / Prev buttons |
| Show / hide the controls (in the player) | OK, or LEFT / RIGHT to show them | tap the video |
| Play / pause | Play / Pause key | pause button |
| Open the channel list (in the player) | RIGHT on the list button (the last button), then OK | list button |
| Close the channel list | LEFT or BACK | tap the video or BACK |
| Pick a channel in the list | UP / DOWN, then OK | tap |
| Leave the player / close a category grid | BACK | back arrow or BACK |

On a TV box the navigation bar is at the top; on a phone it is at the bottom.

## Build the APK with GitHub Actions
1. Create a new GitHub repository and upload everything in this folder (keep the `.github` folder). You can do it in the web page, no git needed.
2. Open the **Actions** tab, click **Build APK** in the left list, then **Run workflow** (choose your branch) and press the green button.
   A push to a branch does **not** start a build; a tag like `v1.0.0` does.
3. Wait a few minutes. A green tick means success. Open the run and download the artifact **Wosux-Stream-apk** at the bottom (a zip with two APKs):
   - `Wosux-Stream.apk`: the release version (smaller, signed). Installs as `com.wosux.stream`.
   - `Wosux-Stream-test.apk`: the debug version. Installs next to the release one as `com.wosux.stream.test`, so you can test without touching your real install.
4. Optional: push a tag such as `v1.0.0` and both APKs are also attached to a **GitHub Release** (handy for downloading directly on the TV box).
5. If the run is red, open it, click the red step and read the red lines.

### Signing (your own key, optional)
By default the APK is signed with the key committed in `app/freetv.keystore` (password `freetv123`), so every build installs over the previous one.
**In a public repo anyone can read that key and password.** That is fine for a personal app. If you want your own private key, add these four
**secrets** (GitHub > Settings > Secrets and variables > Actions > New repository secret):

| Secret | Value |
|---|---|
| `WOSUX_KEYSTORE_BASE64` | your keystore file as base64 text |
| `WOSUX_STORE_PASSWORD` | the keystore password |
| `WOSUX_KEY_ALIAS` | the key alias |
| `WOSUX_KEY_PASSWORD` | the key password |

Set all four, or none. With all four, the workflow decodes the keystore and signs with it; the passwords never appear in the build files.
With none, the build works exactly as before. If only some are set the run stops early with a clear message.

To make a keystore (on a computer with Java) and its base64 text:
```
keytool -genkeypair -v -keystore my.keystore -alias wosux -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 my.keystore        # Linux (macOS: base64 -i my.keystore)
```
Local builds read the same names as environment variables (`WOSUX_STORE_PASSWORD`, `WOSUX_KEY_ALIAS`, `WOSUX_KEY_PASSWORD`) and fall back to the committed values.

## Install
- **Phone / tablet:** open the APK and allow "install unknown apps" when asked.
- **Android box:** copy `Wosux-Stream.apk` to a USB stick and open it with a file manager, or use the free *Downloader* app with your GitHub Release link,
  or `adb connect <box-ip>` then `adb install Wosux-Stream.apk`.

## Notes
- Minimum Android 5.0 (API 21). Version 1.0.0.
- Free streams come and go. Some are geo-blocked or offline: pick another source in **Settings > Channel source**, or use **Refresh playlist now**.
- **Changing the signing key means you must uninstall the old app once** (Android refuses to update an app signed with a different key). Your favorites and recents are then gone.
- The release build is shrunk (R8), so a crash report from `Wosux-Stream.apk` shows short names; the `-test.apk` keeps readable ones.
- Not a channel provider: the app only plays the playlists you point it at. If you ever publish it publicly, you are responsible for the sources you use, and app stores have their own rules about apps that stream third-party content. Read them first.
