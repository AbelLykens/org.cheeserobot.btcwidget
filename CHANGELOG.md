# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.1] - 2026-05-08

### Fixed
- **Widget no longer greys itself out on wake-up.** When the device
  woke from doze and the radio hadn't fully reattached yet, a
  scheduled refresh would briefly see "no network", paint the widget
  in the OFFLINE state (grey icon, dimmed text, no live background),
  and bump an internal failure counter — two such ticks in a row was
  enough to keep the widget greyed out indefinitely. The scheduled
  30-min refresh path now leaves the widget alone on transient
  failures: the launcher's last good frame stays on screen until we
  actually have new data. The "…" loading flash is also limited to
  user-initiated refreshes (taps and "save settings"), so a
  scheduled tick can no longer briefly clobber the visible price.

### Changed
- **Stale styling is now time-based, not failure-based.** The widget
  switches to its greyed-out "stale" treatment once the cached price
  has aged past three hours — about six missed scheduled ticks —
  rather than after two consecutive fetch failures. Transient
  hiccups no longer change how the widget looks; only data that's
  actually getting old does.
- **F-Droid availability.** Cheese BTC Widget is now also published
  on F-Droid: <https://f-droid.org/en/packages/org.cheeserobot.btcwidget>.
  No app behaviour changes, just an extra install channel.

[3.1]: https://github.com/AbelLykens/org.cheeserobot.btcwidget/releases/tag/v3.1

## [3.0] - 2026-05-07

### Added
- **Block height widget mode.** New currency option in the configure
  screen: pick **Block height — latest block** to turn the widget into
  a live bitcoin block-height counter. The miner / pool name (e.g.
  "SpiderPool") sits in the top caption slot, the latest block number
  (e.g. `948,347`) is the main read, and the chart background paints
  a stylised diagonal line going up — block height only ever climbs,
  so the line is the joke. Powered by the new `latest_block` field
  in `summary.json`. The change-percentage indicator hides automatically
  for this mode.

### Changed
- **Single endpoint, all data.** USD, EUR, SATS, the 24-hour and 7-day
  history series, and the latest block are now pulled from one
  consolidated `https://cheeserobot.org/price/summary.json` call
  instead of three different endpoints. Each widget refresh costs
  exactly one HTTP round-trip on the wire — the previous design
  fanned out to as many as three (price, hist-1d, hist-7d) per tick.
  The chart cache and the change-indicator's 24h/7d reference points
  fall straight out of the same payload, so no new caching machinery
  is needed.

[3.0]: https://github.com/AbelLykens/org.cheeserobot.btcwidget/releases/tag/v3.0

## [2.8] - 2026-05-07

### Added
- **Sticky preview in the configure screen.** The logo and live
  preview now stay pinned to the top of the screen while the rest
  of the controls scroll underneath. Toggling any setting always
  updates the preview within view — no more scrolling back up to
  check what your change did.

### Removed
- **"Choose your currency" / "Update your widget settings" header
  copy.** Both the title and the context-switching subtitle were
  decorative; the live preview right below them tells you what
  you're configuring. Removing them shrinks the sticky header so
  more controls fit on screen at once. The Bitcoin logo above the
  preview was also slimmed from 64dp to 40dp for the same reason.
- **Flat green chart for BTC mode.** When the currency is
  Bitcoin (₿) — the "always 1 BTC" easter-egg — the chart
  background paints a single horizontal green line. There's no
  fetch, no historical, no period selector: the line is the joke.

### Changed
- **"Price change" replaces "Price change indicator".** The settings
  category is renamed and now offers just two options — **24h** and
  **7d** — instead of the old Off / 24-hour / 7-day trio. The
  bottom red/green percentage line is therefore always shown when
  upstream data is available; users who previously had it disabled
  see the 24h reading from now on. Existing widgets with a stored
  `OFF` value migrate silently to `1D` on first read; their other
  settings are preserved.
- **Chart background follows the chosen period.** The "Show chart
  background" toggle is no longer hard-wired to 7-day data. Picking
  **24h** in the new section paints the sparkline from the new
  `/price/price-hist-1d.json` endpoint (~25 hourly samples); picking
  **7d** keeps the existing 4-hourly 7-day series. Both endpoints
  are throttled to one fetch per hour each, independently, and only
  pulled when at least one placed widget actually needs them.
- **Chart always ends at "now".** Whenever the sparkline is painted,
  the most recently fetched upstream USD/EUR price is appended as the
  rightmost data point. Up until now the line could end anywhere up
  to an hour in the past (the price-history cache lives at hourly
  resolution); the appended sample closes the gap so the chart's
  right edge matches the price text above it.

[2.8]: https://github.com/AbelLykens/org.cheeserobot.btcwidget/releases/tag/v2.8

## [2.7] - 2026-05-06

### Changed
- **Launcher surfaces fetch errors.** The price preview at the top of
  the main app screen used to load once and then stick — toggling
  airplane mode after first open left the box happily showing stale
  numbers. It now re-fetches every time the activity resumes and every
  time you tap "Refresh existing widgets", and short-circuits with a
  clear bold "No network" headline (plus a "turn on Wi-Fi or mobile
  data" hint) when the device reports no usable connection. Server
  errors and JSON failures get the same treatment with their own
  friendly headlines instead of an opaque exception in parentheses.
- **SATS change indicator is colour-inverted.** Sats-per-USD rises
  when BTC falls, so a "+1.5% 24h" reading on a SATS widget actually
  means BTC just got cheaper. The percentage colour now flips for
  SATS mode — negative is green, positive is red — keeping "green =
  good for Bitcoin" consistent across every currency. The numeric
  sign in the text still reflects the displayed value's own
  direction. The 7-day sparkline colour was deliberately left alone.

### Fixed
- **Resource link failure on the pencil icon.** `ic_edit_pencil.xml`
  referenced `?attr/colorControlNormal`, which is the AppCompat
  namespace; this app intentionally avoids AppCompat (the launcher
  extends plain `Activity` to keep the APK small), so the build
  failed with `attr/colorControlNormal not found`. Switched to
  `?android:attr/colorControlNormal`, which exists on the platform
  theme since API 21 — well below our minSdk of 26.

[2.7]: https://github.com/AbelLykens/org.cheeserobot.btcwidget/releases/tag/v2.7

## [2.6] - 2026-05-06

### Added
- **New Bitcoin logo** baked from `btc.png`: density-correct PNGs for
  every launcher icon mipmap bucket (mdpi → xxxhdpi), and the same
  artwork inside the widget face, the launcher activity, and the
  widget configure screen. The adaptive launcher icon now sits on a
  white background with the orange ring inset 16dp into the safe zone
  so it stays whole on every launcher mask shape.
- **Hide currency icon ($, €, sat).** New display toggle: when off it
  drops the "$" / "€" / "₿" prefix from the price for USD/EUR/BTC
  modes, and hides the sat-symbol icon for SATS mode. Independent of
  "Show Bitcoin logo"; for SATS the two toggles compose.
- **"Moscow Time" easter egg.** Hidden bonus in the configure screen:
  pick **Sats per USD** as the currency *and* a thousands separator,
  and a "Display as Moscow Time" toggle appears under Number format.
  Flipping it on renders the four-digit sats-per-USD figure as a
  colon-split clock — e.g. 1234 sats becomes `12:34`.
- **7-day chart in the configure-screen preview.** The sparkline now
  also paints behind the live preview, so toggling the chart option
  has visible feedback before you save. Uses real cached history when
  available; otherwise synthesises a plausible curve from the sample
  endpoints.

### Changed
- **All display toggles read in the affirmative ("Show X").** The
  former "Hide Bitcoin logo" / "Hide \"1 BTC\" caption" CheckBoxes
  now read "Show Bitcoin logo" / "Show \"1 BTC\" caption" and are
  checked by default. **Show decimal places** moved out of Number
  format into the Display section so all visibility toggles live in
  one place. Underlying preference keys are unchanged — the activity
  inverts at load/save — so existing widgets keep their settings.

### Fixed
- **Numeric and Moscow-Time renderings agree on the last digit.**
  The numeric formatter rounds (HALF_UP), but the Moscow-Time
  formatter used to truncate via `toLong()`, so a value like 1223.7
  rendered as `1 224` numerically but `12:23` as Moscow Time. Both
  now round the same way and produce identical digits.

[2.6]: https://github.com/AbelLykens/org.cheeserobot.btcwidget/releases/tag/v2.6

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
POWER_SAVE_MODE_CHANGED` broadcast is sent with
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
