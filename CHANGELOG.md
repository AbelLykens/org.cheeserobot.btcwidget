# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.5] - 2026-05-06

### Added
- **Optional 7-day chart background.** The widget can now paint a faint
  sparkline of the last week of BTC prices behind the price text. Line
  is green when the 7-day performance is up, red when down. Driven by a
  new `cheeserobot.org/price/price-hist-7d.json` endpoint, fetched at
  most once per hour regardless of how often you tap the widget. Toggle
  it under **Advanced options → Show 7-day chart background** (on by
  default). Failures of the history endpoint never affect the price
  display — the widget falls back to the static panel.

[2.5]: https://github.com/AbelLykens/org.cheeserobot.btcwidget/releases/tag/v2.5

## [2.4] - 2026-05-06

### Added
- **"Sats per USD" currency.** Pick it from the currency list to see how
  many satoshis one US dollar buys, with the Font Awesome sat symbol
  shown in the icon slot. The value is derived from the existing USD
  price feed (no extra network call), and the 24h/7d change indicator
  works in this mode too — note that "+x%" for sats means the dollar
  bought *more* sats than before, i.e. BTC dropped.

[2.4]: https://github.com/AbelLykens/org.cheeserobot.btcwidget/releases/tag/v2.4

## [2.3] - 2026-05-05

A big release: the widget now shows a 24-hour or 7-day price-change
indicator, refreshes every placed widget at once on a single shared
network call, and the settings screen has been reorganised around a
live preview.

### Added
- **Price change indicator.** Optional red/green percentage line at the
  bottom of the widget showing 24-hour or 7-day change vs the current
  price. Off by default; pick "24h" or "7d" under Advanced options.
  Driven by the new `price_1d_ago_*` / `price_1w_ago_*` fields the
  upstream feed now exposes.
- **Refresh-all on tap.** Tapping any widget now refreshes every placed
  widget on the device. The feed returns USD and EUR (and historicals)
  in one JSON, so a single HTTP call covers everyone — multi-widget
  setups stay in sync and don't make redundant requests.
- **15-second rate limit** on user-triggered refreshes. A second tap
  inside the window repaints from cache instead of hitting the wire.
  System-scheduled updates (every 30 min) and the settings-save path
  bypass the limit.
- **Live widget preview** at the top of the settings screen, sitting on
  a checkered grey background so opacity changes are visible without
  closing the screen. Updates in place on every input change.
- **Two-tier settings.** The configure screen now shows just the
  currency picker by default. An "Advanced options" header expands to
  reveal tracked amount, formatting, opacity, display toggles, and the
  change indicator. Auto-expands on reconfigure when any advanced
  setting is non-default.
- **Version footer** at the bottom of the settings screen.

### Changed
- **Bitcoin logo now lines up with the price text vertically.** The
  widget layout was restructured so the icon and the price share a
  horizontal row, with the "1 BTC" caption as a sibling above. The
  icon's centre now aligns with the price number's centre rather than
  the centre of the icon-plus-caption stack.
- **Robust JSON parsing.** Currency lookup tokenises keys and excludes
  any key containing "ago", so "price_1d_ago_usd" can never shadow
  today's "price_usd" regardless of key order.

### Fixed
- **No more redundant fetches** when multiple widgets share a currency
  — previously each widget's refresh fired its own HTTP request.

[2.3]: https://github.com/AbelLykens/org.cheeserobot.btcwidget/releases/tag/v2.3

## [2.1] - 2026-05-05

### Changed
- **Widget face restyled.** The icon-and-text group is now centered as
  a single block, so hiding the Bitcoin logo no longer shifts the
  remaining text off-center. Auto-sizing of the price has a real
  vertical bound now, so prices fill the widget vertically instead of
  rendering small.
- Migrated the Gradle build from the standalone
  `org.jetbrains.kotlin.android` plugin to AGP 9's built-in Kotlin
  support, fixing a "kotlin extension already registered" collision
  with AGP 9.0.1 + Kotlin 2.2.10.

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

[2.1]: https://github.com/AbelLykens/org.cheeserobot.btcwidget/releases/tag/v2.1
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
