# AAYT — YouTube for Android Auto

**A standalone, sideloadable YouTube viewer for Android Auto head units.**
A native video grid with YouTube search, account feeds, local bookmarks, and a fullscreen YouTube WebView player. No address bar or browser tabs.

AAYT is a GPLv3 derivative of [AABrowser](https://github.com/kododake/AABrowser) by kododake, which pioneered the technique of projecting a full Android activity onto Android Auto head units. `SPEC.md` describes an earlier WebView-only design; the current implementation uses native feeds and controls.

> [!CAUTION]
> **Safety first.** This app is for passengers or for when you are safely parked. If you are driving, do not watch this screen. You are responsible for complying with local laws.

> [!NOTE]
> YouTube is a trademark of Google LLC. AAYT is unofficial and is not affiliated with or endorsed by Google. Feeds use YouTube's internal Innertube endpoints; playback uses Android System WebView. There is no ad blocker.

## ✨ Features

- 🚗 **Runs on the head unit** using the AABrowser projection technique (`CAR_LAUNCHER` activity + distraction-optimized metadata + AA "Unknown sources").
- ▶️ **Native video grid**: typed search, personalized Home and Subscriptions when signed in.
- 🖥️ **Fullscreen playback** with system bars hidden; screen never blanks while the app is up.
- 🔐 **Google sign-in works** (Chrome user-agent + Client Hints identity spoof).
- 🔒 **HTTPS transport**: cleartext requests are disabled. The login/player WebViews do not implement a domain allowlist.
- 🌓 **Light / AMOLED-dark themes**.
- 📱 **Share from phone**: share a YouTube watch, Shorts, Live, or `youtu.be` video link to AAYT and choose **Play now** or **Save for later**. Shared timestamps are preserved; tracking query parameters are discarded. Android may also offer AAYT under Open with for supported links; sharing does not require changing default link settings.
- 🔖 **Local Saved videos**: tap the bookmark on a video card or in the player, then open **Saved** from the rail or menu. Tap a filled bookmark to remove it; **Undo** restores it. Shared videos can have an optional name. Bookmarks require no sign-in, are excluded from Android backup/transfer, and do not sync with YouTube's Watch Later playlist.
- 🕵️ **No app-owned analytics or update checker.** Native API requests, thumbnails and WebViews still communicate with Google/YouTube. Signed-in requests reuse the YouTube session cookies. This is not a promise that Google performs no tracking.

Saved videos are links, not downloaded videos. Thumbnails and playback need internet access. No captions controls or voice-search feature are implemented in the native UI. Profile opens YouTube's web library as an alternative for account playlists/history.

## 📋 Requirements

- Android **15+** on the phone
- Android Auto with **Developer settings → Unknown sources** enabled

## 📲 Install

1. Download `AAYT-<version>.apk` and install it on your phone.
2. In your phone's settings, search **Android Auto** → tap **Version** 10 times to unlock developer mode.
3. Open the **⋮ menu → Developer settings → enable "Unknown sources"**.
4. Connect to your car — AAYT appears in the Android Auto launcher.

## 🛠️ Build

```bash
./gradlew assembleDebug     # → app/build/renamedApks/debug/AAYT-<version>_debug.apk
./gradlew assembleRelease   # requires signing config (see app/build.gradle.kts)
./gradlew test              # unit tests (URL policy, bookmarks, account headers)
```

Requires JDK 21 and Android SDK Platform 36.

### Testing with the Desktop Head Unit (DHU)

1. `sdkmanager "extras;google;auto"` — installs the DHU.
2. On the phone: Android Auto developer settings → **Start head unit server**.
3. `adb forward tcp:5277 tcp:5277`
4. Run `$ANDROID_HOME/extras/google/auto/desktop-head-unit`.

## ⚖️ License

[GPLv3](LICENSE). Based on [AABrowser](https://github.com/kododake/AABrowser) © kododake.
