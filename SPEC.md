# AAYT — Standalone YouTube App for Android Auto

**Spec version:** 1.0 · **Date:** 2026-07-13 · **Status:** Approved for implementation
**Base:** Fork of AABrowser v2.2 (GPLv3, © kododake) — a WebView browser that projects a full
Android Activity onto Android Auto head units.

---

## 1. Product definition

### 1.1 Goal

Convert the general-purpose AABrowser into **AAYT**: a single-purpose, sideloadable app whose
only job is to present YouTube (web) on an Android Auto head unit — full screen video,
voice search, sign-in support — with no general browsing capability.

### 1.2 Identity

| Item | Value |
|---|---|
| App name | `AAYT` |
| Application ID / namespace | `com.nrknithin.aayt` |
| Version | `1.0` (versionCode 1) |
| License | GPLv3 (derivative of AABrowser; attribution retained) |
| Branding | **No YouTube trademark assets.** Generic play-glyph launcher icon; the name "AAYT" is used, never "YouTube" alone as the app label. |

### 1.3 Non-goals

- No URL/address bar, no multi-tab browsing, no bookmarks, no start-page dashboard.
- No YouTube Data API / no scraping / no ad-blocking / no background playback — the app is a
  pinned browser rendering YouTube's own web app as-is.
- No Play Store distribution (video on AA head units cannot pass review; sideload +
  AA "Unknown sources" remains the distribution model).
- TV UI (`youtube.com/tv`) is **out of scope** for v1 (UA-gated by Google, fragile).

### 1.4 Safety framing (retained from upstream)

Parked/passenger use only. The README keeps the driver-distraction warning. The app never
attempts to defeat AA driving-state restrictions beyond what the upstream projection
mechanism already does.

---

## 2. Functional requirements

| ID | Requirement |
|---|---|
| FR-1 | On launch, load `https://m.youtube.com` in a single persistent WebView (mobile Chrome UA profile with matching Client-Hints metadata). |
| FR-2 | If "Resume last page" is enabled (default ON) and a previous YouTube URL was persisted, load it instead of the home page. |
| FR-3 | **Domain lock:** main-frame navigations are only permitted to the allowlist in §4.3. Any other main-frame navigation is swallowed; a toast "AAYT only opens YouTube" is shown. *(v1.2: allowlist broadened to the whole Google family incl. country TLDs — `google.co.in` etc. — after sign-in redirects were blocked in the field.)* |
| FR-4 | HTML5 fullscreen (`onShowCustomView`) works; the video fills the entire screen with system bars hidden. Back exits fullscreen first. |
| FR-5 | YouTube's microphone/voice-search button works via the native `webkitSpeechRecognition` polyfill (SpeechRecognitionBridge) with runtime RECORD_AUDIO permission flow. |
| FR-6 | Google sign-in works inside the WebView (Chrome UA + UserAgentMetadata spoof retained verbatim from upstream). Cookies/session persist across launches. |
| FR-7 | DRM playback: `RESOURCE_PROTECTED_MEDIA_ID` is auto-granted (Widevine L3). |
| FR-8 | *(v1.2)* **AAYT car UI:** a native side rail (64dp, car-app style) hosts Menu, Home, Search, Subscriptions, Library and Back. An injected "car skin" hides YouTube's own bottom pivot bar and app-upsell chrome so the web content reads as the app's content pane. Search triggers YouTube's own search UI via injected JS (URL fallback `/results`). The `AAYTBridge.openMenu()` JS interface remains for the bundled error page. The menu overlay offers: Back, Forward, Reload, Home, Layout selector (Mobile/Desktop/TV β), Settings, app version. *(v1.1's injected pivot-bar item and corner chip are superseded by the rail.)* |
| FR-9 | Settings surface (§5): theme (System/Light/AMOLED dark), beta dark pages, global display scale, resume-last-page toggle, privacy data clearing. |
| FR-14 | *(v1.1)* The activity is locked to `sensorLandscape` — head units are landscape. |
| FR-15 | *(v1.1)* **TV UI mode (experimental):** a Cobalt (YouTube-on-TV browser) user-agent identity + `youtube.com/tv`. Google gates this UI for non-TV browsers and it expects d-pad input, so touch works only partially — hence β labelling and not the default. Layout modes: Mobile (default, `m.youtube.com`), Desktop (`www.youtube.com`, Windows Chrome UA), TV β. |
| FR-10 | `VIEW` intents for `youtube.com` / `www.youtube.com` / `m.youtube.com` / `youtu.be` hosts open in the app (share a video from the phone → plays in AAYT). Non-YouTube VIEW intents are not claimed. |
| FR-11 | `FLAG_KEEP_SCREEN_ON` is set unconditionally while AAYT is in the foreground (design default for a head-unit video app; no settings toggle, fullscreen exit does not clear it). |
| FR-12 | Network/HTTP errors on the main frame show the bundled `error.html` with a retry affordance. |
| FR-13 | The Android Auto projection contract is preserved **verbatim**: `CAR_LAUNCHER`/`NAVIGATION`/`APP_MAPS` categories, `distractionOptimized` metadata, `minCarApiLevel`, `automotive_app_desc`, activity `<layout>` defaults, `appCategory="game"`, car-app permissions. |

## 3. Non-functional requirements

| ID | Requirement |
|---|---|
| NFR-1 | **Zero telemetry.** UmamiTracker and its endpoint are deleted; no network calls except WebView traffic. |
| NFR-2 | No upstream-owned surfaces remain: kododake update-checker, FreeDroidWarn dialog, GitHub links point to nothing upstream-owned. |
| NFR-3 | Dependencies pruned: remove `okhttp`, `zxing`, `oss-licenses` (plugin + activity), `FreeDroidWarn`. Remove `kotlinx-serialization` if no JSON persistence remains. |
| NFR-4 | minSdk 35 / targetSdk 36 / compileSdk 36, JDK 21, AGP 9.1.1. (Amended from upstream's 37: SDK Platform 37 exists only on the canary channel, so the fork pins the latest stable platform 36 and core-ktx 1.17.0 accordingly.) |
| NFR-5 | Release build passes R8 (`assembleRelease` compiles; signing config optional). |
| NFR-6 | GPLv3 license file retained; README credits AABrowser as the upstream project. |
| NFR-7 | HTTP (cleartext) is not supported: `usesCleartextTraffic=false`; `http://` YouTube URLs are upgraded to `https://` instead of prompting. All cleartext-consent machinery is deleted. |

---

## 4. Architecture

### 4.1 What is kept (the load-bearing 50%)

| Component | Notes |
|---|---|
| Manifest car metadata + activity layout block | Verbatim (FR-13). |
| `web/ConfiguredWebView.kt` | Core WebView config: JS, DOM storage, autoplay w/o gesture, cookies, UA + Client-Hints spoofing, DRM grant, fullscreen hooks. Simplified: cleartext handling removed, client-cert removed, SSL errors auto-cancel to error page. |
| `web/SpeechRecognitionBridge.kt` | Unchanged (voice search). |
| `ui/BrowserUIManager.kt` → slimmed | Fullscreen enter/exit, FAB behavior. Address-bar code deleted. |
| `permissions/PermissionManager.kt` → slimmed | RECORD_AUDIO + notification permission only. Geolocation + cleartext dialogs deleted. |
| `ui/ThemeManager.kt`, global-scale support | Unchanged. |
| `settings/` | Rebuilt with the §5 surface only. |
| `assets/error.html` | Kept. |

### 4.2 What is deleted

`analytics/`, `bookmarks/`, `startpage/`, `tabs/` (single WebView replaces TabManager),
`ShareBookmarkActivity`, `ui/QRUtils.kt`, `ui/adapters/*`, `data/SiteIconCache.kt`,
`web/ClientCertHandler.kt`, `web/CryptoHelper.kt` (if only used by deleted code),
check-latest overlay, FreeDroidWarn dialog + dependency, OSS-licenses activity + plugin,
address bars (persistent + menu), QR view, cleartext dialogs, location permissions,
`presentation_browser.xml` (unused presentation layout) — plus all orphaned layouts,
drawables, strings, colors, and preference keys.

### 4.3 Domain allowlist (main-frame navigation)

Host matches (exact or subdomain-of):

```
youtube.com, m.youtube.com, www.youtube.com, music.youtube.com,
youtu.be, youtube-nocookie.com,
accounts.google.com, accounts.youtube.com, myaccount.google.com,
google.com (sign-in redirects only), gds.google.com,
googlevideo.com, ytimg.com, ggpht.com,
gstatic.com, googleapis.com, googleusercontent.com
```

Enforced in `shouldOverrideUrlLoading` for main-frame loads via a new
`web/YouTubeUrlPolicy.kt` (pure function + unit tests). Subresource requests are not
intercepted (YouTube's CDN set churns; blocking subresources breaks playback).
`http://` on an allowed host → rewritten to `https://`. Anything else → blocked + toast.

### 4.4 New/rewritten modules

| File | Responsibility |
|---|---|
| `web/YouTubeUrlPolicy.kt` (new) | Allowlist decision + https upgrade + `youtu.be`→watch URL passthrough. Pure, unit-tested. |
| `MainActivity.kt` (rewrite) | Owns the single WebView lifecycle, wires managers, back-dispatch (fullscreen → overlay → webView.goBack → finish). |
| `data/Preferences.kt` (rewrite of BrowserPreferences) | Only §5 keys + last-URL persistence + scaled-context helper. |
| `AppConstants.kt` | `YOUTUBE_HOME_URL`, repo URL for this fork, request codes. |

### 4.5 Deliberately retained oddities

- `appCategory="game"` and the `androidx.car.app.*` permissions: part of the projection
  hack; do not "clean up".
- `POST_NOTIFICATIONS` permission + first-launch prompt: retained conservatively (AA surfaces
  app notifications; upstream ships it).
- `androidx.car.app` dependency + `minCarApiLevel` metadata: retained.

---

## 5. Settings surface (complete list)

| Setting | Values | Default |
|---|---|---|
| Theme | System / Light / AMOLED dark | System |
| Dark pages (beta) | on/off | off |
| Global display scale | 40–200% (presets + custom) | 100% |
| Resume last page | on/off | on |

The layout selector (Mobile / Desktop / TV β) lives in the menu overlay, not in settings.
*(v1.1: all floating-button settings removed along with the button itself — FR-8.)*

---

## 6. Work packages & acceptance criteria

| WP | Scope | Acceptance criteria |
|---|---|---|
| **WP1** Rebrand + build | appId/namespace → `com.nrknithin.aayt`; version 1.0/1; deps pruned (NFR-3); APK rename task outputs `AAYT-1.0.apk`; new launcher icon (generic play glyph) | `assembleDebug` green; `grep -r kododake app/src` → 0 hits; no okhttp/zxing/oss-licenses/FreeDroidWarn in dependency tree |
| **WP2** Manifest + resources | Car metadata verbatim; VIEW filter restricted to YouTube hosts; ShareBookmarkActivity + OSS activity removed; location perms removed; cleartext off; orphaned res files deleted | `manifest-merger` output verified; lint passes for unused-resources on touched set |
| **WP3** Core | Single WebView; YouTubeUrlPolicy + tests; MainActivity rewrite; fullscreen, speech, DRM, session persistence work | Unit tests for URL policy pass; manual matrix §7.2 |
| **WP4** Settings | §5 surface only; Preferences rewrite | Settings open, persist, apply live (theme/scale recreate) |
| **WP5** Docs | README rewritten (install, AA unknown sources, DHU testing, credits, GPL) ; SPEC.md committed | Docs render, no upstream-owned links except credit line |
| **WP6** Build | Debug + release compile; R8 config still valid | `assembleDebug` + `assembleRelease` exit 0 |
| **WP7** Review | Multi-agent adversarial review (correctness, dead-code, manifest/AA contract, security/privacy, GPL/rebrand) → fixes applied | 0 confirmed critical findings open |
| **WP8** Emulator | AVD (API 36 google_apis, x86_64) boots; APK installs; app launches; screenshot shows YouTube rendered | `adb exec-out screencap` shows m.youtube.com UI |
| **WP9** DHU | `extras;google;auto` installed; DHU launches; projection attempted against emulator/phone; scripted `run-dhu.sh` + documented manual steps for the Google-sign-in-gated parts | DHU binary runs; procedure documented end-to-end; projection verified if AA head-unit server is attainable in this environment |

## 7. Test plan

### 7.1 Automated

- `./gradlew test` — YouTubeUrlPolicy unit tests (allow/deny/upgrade/youtu.be cases).
- `./gradlew assembleDebug assembleRelease`.
- Emulator smoke: boot headless AVD → install → `am start` → wait → screencap → visual check
  (YouTube shell rendered, no crash in logcat, `mediaPlaybackRequiresUserGesture=false` path
  exercised by opening a watch URL via VIEW intent).

### 7.2 Manual (documented for head-unit / DHU)

| Case | Expected |
|---|---|
| Launch on head unit via AA launcher | YouTube home renders full-bleed |
| Tap video → fullscreen | Player fills screen, bars hidden |
| Back in fullscreen | Exits fullscreen, stays on watch page |
| Mic button in YouTube search | Native speech recognizer feeds results back |
| Sign-in flow | accounts.google.com completes without `disallowed_useragent` |
| External link in description | Blocked with toast |
| Share youtu.be link from phone | Opens in AAYT |
| Relaunch | Resumes last page (setting ON) |

### 7.3 DHU procedure (WP9)

1. `sdkmanager "extras;google;auto"` (done by bootstrap).
2. Head-unit server requires the **Android Auto companion app** with developer mode on the
   *phone-side* device. On emulators this needs a `google_apis_playstore` image + Play
   sign-in (interactive) — scripted up to the sign-in wall; remaining steps documented.
3. `adb forward tcp:5277 tcp:5277` → launch `desktop-head-unit`.
4. Validate AAYT appears in the AA launcher (unknown sources + developer settings enabled)
   and projects.

## 8. Risks

| Risk | Mitigation |
|---|---|
| Google blocks WebView sign-in despite UA spoof | Keep Client-Hints metadata in sync with a current Chrome version constant; document sign-in-on-phone-first cookie path |
| YouTube web UI changes break layout | App is a thin shell; nothing DOM-coupled except optional future JS injection (none in v1) |
| AA unknown-sources requirement changes | Same exposure as upstream; document |
| Trademark exposure | No YouTube logo/wordmark assets; generic branding |
| DHU e2e blocked by Play sign-in in headless env | Deliver scripted setup + precise manual runbook; verify everything scriptable |

## 9. Milestones / execution order

1. WP1 → WP2 → WP3 → WP4 → WP5 (implementation, sequential — heavily interdependent)
2. WP6 build gate
3. WP7 adversarial multi-agent review → fix → re-build
4. WP8 emulator smoke test (SDK bootstrap runs in parallel from T0)
5. WP9 DHU
