# AAYT — YouTube for Android Auto

**A standalone, sideloadable YouTube viewer for Android Auto head units.**
One fullscreen WebView locked to YouTube: video playback, voice search, Google sign-in — and nothing else. No address bar, no tabs, no general browsing.

AAYT is a GPLv3 derivative of [AABrowser](https://github.com/kododake/AABrowser) by kododake, which pioneered the technique of projecting a full Android activity onto Android Auto head units. All browser features were removed and the app was locked to YouTube. See [SPEC.md](SPEC.md) for the full specification.

> [!CAUTION]
> **Safety first.** This app is for passengers or for when you are safely parked. If you are driving, do not watch this screen. You are responsible for complying with local laws.

> [!NOTE]
> YouTube is a trademark of Google LLC. AAYT is an unofficial viewer that simply displays the YouTube website in an Android System WebView. It is not affiliated with or endorsed by Google, does not use the YouTube API, and does not modify, scrape, or ad-block the site.

## ✨ Features

- 🚗 **Runs on the head unit** using the AABrowser projection technique (`CAR_LAUNCHER` activity + distraction-optimized metadata + AA "Unknown sources").
- ▶️ **Full YouTube web app**: browse, search, playlists, subscriptions, watch history.
- 🖥️ **Fullscreen playback** with system bars hidden; screen never blanks while the app is up.
- 🎙️ **Voice search** — YouTube's mic button works via a native `webkitSpeechRecognition` polyfill.
- 🔐 **Google sign-in works** (Chrome user-agent + Client Hints identity spoof).
- 🔒 **Domain lock**: only YouTube + the Google domains its auth/CDN need; everything else is blocked. HTTPS-only.
- 🌓 **Light / AMOLED-dark themes**, global display scale, optional desktop layout.
- 📱 **Share from phone**: `youtube.com` / `youtu.be` links open directly in AAYT.
- 🕵️ **Zero telemetry.** No analytics, no update pings — the only network traffic is the WebView itself.

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
./gradlew test              # unit tests (URL policy)
```

Requires JDK 21 and Android SDK Platform 36.

### Testing with the Desktop Head Unit (DHU)

1. `sdkmanager "extras;google;auto"` — installs the DHU.
2. On the phone: Android Auto developer settings → **Start head unit server**.
3. `adb forward tcp:5277 tcp:5277`
4. Run `$ANDROID_HOME/extras/google/auto/desktop-head-unit`.

## ⚖️ License

[GPLv3](LICENSE). Based on [AABrowser](https://github.com/kododake/AABrowser) © kododake.
