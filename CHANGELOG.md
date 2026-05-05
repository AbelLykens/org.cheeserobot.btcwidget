# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0] - 2026-05-04

A big quality-of-life release. The widget is now configurable from the
home screen pencil, the main app surfaces fetch errors itself (no more
notifications), and the visual design has been refreshed.

### Added
- **"Add widget to home screen" button** on the main app screen. Uses
  `AppWidgetManager.requestPinAppWidget` so modern launchers pop up a
  preview-and-confirm dialog; once the user accepts, the configure
  screen opens automatically.
- **Reconfigurable widgets**. Tap the pencil icon while moving a widget
  on your home screen (Android 12+) to reopen its settings — existing
  choices are pre-filled.
- **Three currencies**: USD, EUR, and a "Bitcoin (₿)" easter-egg mode
  that always shows `₿ <tracked amount>` without making a network call.
- **Tracked amount** setting (default 1.0). Set it to 0.5 to see what
  half a Bitcoin is worth, or 2 to see twice.
- **Show-decimals toggle**: `$78,875.23` instead of `$78,875`.
- **Thousands-separator picker**: auto (locale default), comma, dot,
  space, or none.
- **Background opacity slider** (0–100%).
- **Hide options** for the Bitcoin logo and the "1 BTC" caption.
- **Battery-saver awareness**: when battery saver is on, the icon greys
  out and tapping the widget opens a small dialog explaining that
  updates are paused, with a one-tap shortcut into the system battery
  settings. The icon flips back to colour the moment the user turns
  battery saver off from that dialog.
- **Offline detection**: the widget pre-checks connectivity and shows
  the last known price greyed out instead of burning retries on a
  guaranteed failure.
- **Retry on failure** (one extra attempt after a short delay).
- **Cached-price persistence**: the last successfully fetched price is
  stored and re-displayed (with a greyed icon) on every failure mode —
  battery saver, offline, generic fetch error.
- **Per-widget status panel** in the launcher activity: each placed
  widget gets a line showing its currency, last price, and either
  "updated N min ago" or the most recent error reason.

### Changed
- **New Bitcoin logo** for both the app icon and the widget face:
  orange ring outline with an orange "B" inside.
- **Significantly smaller APK**: dropped the AppCompat and Material
  dependencies (which together pulled in hundreds of unused PNG and
  9-patch drawables), enabled R8 minification + resource shrinking for
  release builds, and limited bundled locales to English.

### Removed
- **System notifications and the POST_NOTIFICATIONS permission.** Fetch
  errors are now reported in the main app, not in your notification
  shade.

### Fixed
- **Battery-saver "off" was not detected.** The
  `ACTION_POWER_SAVE_MODE_CHANGED` broadcast is sent with
  `FLAG_RECEIVER_REGISTERED_ONLY` by the system, so a manifest receiver
  never sees it. The dynamic listener now lives inside the battery-
  saver dialog activity, which broadcasts a refresh on dismiss so the
  widget repaints against the *current* state every time.
- **Battery-saver Toast was suppressed** on devices where the user had
  disabled notifications. Replaced with a dialog Activity, which isn't
  subject to that suppression.

[2.0]: https://github.com/AbelLykens/org.cheeserobot.btcwidget/releases/tag/v2.0

## [1.0] - 2026-05-04

### Added
- Initial release.
- Home-screen widget that displays the latest BTC price from
  `https://cheeserobot.org/price/latest.json`.
- USD / EUR picker shown when the widget is added; each placed widget
  remembers its own currency.
- Auto-sizing price text and a fixed Bitcoin logo.
- Tap-to-refresh and ~30 minute automatic refresh.
- Launcher activity with a live price preview, "How to add the widget"
  steps, and a "Refresh existing widgets" button.
- Error notifications surfacing the actual fetch failure reason
  (network exception, HTTP status + body snippet, JSON parse error,
  or "currency key missing").
- JVM unit tests for the JSON traversal logic.

[1.0]: https://github.com/AbelLykens/org.cheeserobot.btcwidget/releases/tag/v1.0
