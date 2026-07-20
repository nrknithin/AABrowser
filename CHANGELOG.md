# Changelog

## 1.2 (2026-07-20)

- **AAYT car UI.** Native side rail (car-app style): Menu, Home, Search, Subscriptions,
  Library, Back. The injected "car skin" hides YouTube's bottom bar and "Open App" upsells,
  so YouTube's content becomes the app's content pane. Supersedes v1.1's injected
  pivot-bar item and corner chip.
- **Google sign-in fix.** The domain allowlist now covers the whole Google family including
  country domains (accounts.google.co.in etc.) — auth redirects were being blocked.
- Fullscreen video now overlays the rail (fullscreen container moved to window level).

## 1.1 (2026-07-20)

- **Floating button removed.** The AAYT menu now lives inside YouTube's own UI via script
  injection: a fourth "AAYT" item in the bottom navigation bar (Home / Shorts / You / AAYT),
  and a small corner chip on pages without that bar (watch pages, desktop, TV). Built
  Trusted-Types-safe (YouTube's CSP forbids innerHTML).
- **TV UI mode (experimental).** New Layout selector in the menu: Mobile / Desktop / TV β.
  TV mode spoofs a Cobalt (YouTube-on-TV) identity and loads youtube.com/tv — the real
  leanback interface. Touch support there is partial (it expects d-pad input), hence the β.
- **Landscape lock.** The activity is fixed to sensorLandscape — head units are landscape.
- Review fixes: upstream FUNDING.yml removed, error-page retry no longer corrupts
  percent-escaped URLs, error page gained an "AAYT menu" button.

## 1.0 (2026-07-13)

First release of **AAYT** — a standalone YouTube app for Android Auto, forked from AABrowser v2.2.

### Changed
- Repurposed the AABrowser WebView shell into a single-purpose YouTube viewer (`com.nrknithin.aayt`).
- Single persistent WebView; launches straight into YouTube (resumes your last page by default).
- Main-frame navigation locked to YouTube + Google auth/CDN domains; HTTPS-only with automatic http→https upgrade.
- Quick-action menu reduced to Back / Reload / Forward / Home / Settings + desktop-layout switch.
- Settings reduced to: theme, beta page darkening, display scale, resume-on-launch, floating-button options, privacy data clearing.
- Screen is always kept on while the app is in the foreground.
- `youtube.com` / `youtu.be` share links from the phone open in AAYT.

### Removed
- Address bars, tabs, bookmarks, start-page dashboard, QR sharing, home-page override, update checker, cleartext-HTTP consent flow, geolocation support, client-certificate support, OSS-licenses screen.
- **All telemetry** (upstream Umami analytics) and third-party dependencies: okhttp, zxing, kotlinx-serialization, oss-licenses, FreeDroidWarn.

### Kept from upstream (AABrowser © kododake, GPLv3)
- The Android Auto projection contract (car-launcher activity, distraction-optimized metadata).
- WebView engine configuration: Chrome UA + Client Hints spoof (Google sign-in), Widevine DRM grant, HTML5 fullscreen, autoplay without gesture.
- Native speech-recognition polyfill powering YouTube voice search.
- Light/AMOLED theming and global display scale.
