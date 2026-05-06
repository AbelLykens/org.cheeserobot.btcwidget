# Cheese BTC Widget

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A tiny, no-nonsense Android home-screen widget that shows the latest Bitcoin
price at a glance. No accounts, no ads, no tracking — just the number.

Source: <https://github.com/AbelLykens/org.cheeserobot.btcwidget>
Zapstore: <https://zapstore.dev/apps/org.cheeserobot.btcwidget>
Price feed: [`https://cheeserobot.org/price/latest.json`](https://cheeserobot.org/price/latest.json)

---

## Features

- **One-glance price**. Bitcoin logo, currency symbol, and the price as a
  whole number. That's it.
- **USD or EUR** — pick which one you want when you add the widget.
- **Multiple widgets, multiple currencies**. Add it twice if you want both
  `$` and `€` side by side on your home screen.
- **Auto-refresh** roughly every 30 minutes (Android's minimum for
  home-screen widgets).
- **Tap to refresh** — don't want to wait? Tap the widget for an immediate
  update.
- **Dark mode aware**. The background follows your system theme.
- **Locale-aware number formatting**. `65,432` for English locales,
  `65.432` for European locales, etc.
- **Honest about errors**. If the network is down or the API is misbehaving,
  the widget shows `!` and posts a notification telling you exactly what
  went wrong (no internet, HTTP error, bad data, etc.) so you're not left
  guessing.
- **Tiny footprint**. No background services, no analytics, no third-party
  SDKs. The app contacts exactly one host: `cheeserobot.org`.
- **Free and open source** (MIT). Aiming for F-Droid.

---

## Installing the app

### From Zapstore

Available on Zapstore:
<https://zapstore.dev/apps/org.cheeserobot.btcwidget>

Open the link in the Zapstore app (or browse to it from inside the app)
and tap install.

### From F-Droid (recommended once published)

Search for **Cheese BTC Widget** in the F-Droid client and tap install.

### Sideloading the APK

If you have a debug build:

1. Download `app-debug.apk` from the project's releases page (or build it
   yourself — see [Building from source](#building-from-source)).
2. On your phone, allow installs from unknown sources for your file
   manager or browser.
3. Open the APK and tap **Install**.

The app has **no launcher icon** — that's intentional. The widget itself
is the entire app, so you won't see "Cheese BTC Widget" in your app
drawer. Don't worry, it's installed.

---

## Adding the widget to your home screen

1. **Long-press an empty spot** on your home screen.
2. Tap **Widgets** in the menu that appears.
3. Scroll to **Cheese BTC Widget** and drag **BTC Price** onto the
   home screen.
4. A small picker pops up. Tap **US Dollars ($)** or **Euros (€)**.
5. The widget loads with the current price within a few seconds.

Want both currencies? Just repeat the steps and pick the other one the
second time. The widgets are independent.

### Resizing

Long-press the widget and drag the resize handles. The layout scales
nicely from the default 2×1 cells up to roughly 4×2.

### Removing it

Long-press the widget and drag it to **Remove** at the top of the screen.
Your currency choice is forgotten — if you re-add it later it will ask
you again.

---

## Using the widget

- **Tap the widget** to force an immediate refresh. Useful when the price
  is moving and you don't want to wait for the next 30-minute tick.
- **Auto-refresh** happens about every 30 minutes. Android may delay it
  if your phone is in deep doze mode (battery saver, screen off for a
  long time, etc.) — that's a system-level limitation, not a bug in the
  widget.
- **No connectivity?** The widget keeps showing the last price it
  successfully fetched, but the next refresh will show `!` until the
  network comes back. Tap the `!` (or the notification it posts) to
  retry.

---

## Troubleshooting

### The widget shows `!`

Something went wrong fetching the price. The widget posts an Android
notification with the actual cause — pull down your notification shade
and read it. Common causes:

- **No internet / Wi-Fi captive portal.** Connect to a working network
  and tap the widget to retry.
- **API temporarily down.** `cheeserobot.org` may be having a moment.
  Wait a few minutes and tap to retry.
- **Notifications disabled.** On Android 13+, the app needs notification
  permission to show you *why* it failed. Go to **Settings → Apps →
  Cheese BTC Widget → Notifications** and enable them. (The price
  itself doesn't need this permission — only the error message does.)

### The widget shows `…` and never updates

The first fetch hasn't completed yet. Give it 10–20 seconds. If it stays
that way, tap it once to kick off a refresh.

### The price looks wrong / hasn't moved in hours

Android caps widget refreshes at 30 minutes. If you want the absolute
latest, tap the widget — that fetches immediately.

### I added the widget but there's no picker

Some launchers (rare) skip the configuration step and add the widget
with a default currency (USD). Remove it and re-add it from the standard
**Long-press → Widgets** flow.

### I want to change the currency

Currency is set when you add the widget and isn't editable afterward.
Remove the widget and add a fresh one — it'll ask again.

---

## Privacy

The widget does exactly one thing over the network: a `GET` request to
`https://cheeserobot.org/price/latest.json`, every ~30 minutes and on
tap. No analytics, no telemetry, no ad networks, no account required.

---

## Building from source

### Easiest: Android Studio

1. Open Android Studio (Hedgehog 2023.1.1 or newer recommended).
2. **File → Open…** and select this `CheeseWidget` folder.
3. Let it sync. It will download the Gradle wrapper, the Android Gradle
   Plugin (8.2.2) and the SDK pieces it needs.
4. Plug in a phone with USB debugging on, or start an emulator.
5. Click **Run ▶**. The app installs (it has no launcher activity by default
   — that's normal; the widget itself is the entry point).

### From the command line

The project ships without the binary `gradle-wrapper.jar`. Generate it once
with a system Gradle install (8.0+):

```bash
cd CheeseWidget
gradle wrapper --gradle-version 8.4
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Project layout

```
CheeseWidget/
├── build.gradle.kts          # Project-level Gradle config
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts      # App-module Gradle config
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/org/cheeserobot/btcwidget/
        │   ├── BitcoinPriceWidgetProvider.kt   # AppWidgetProvider, drives updates
        │   ├── WidgetConfigActivity.kt          # USD/EUR picker shown on Add
        │   ├── PriceFetcher.kt                  # HTTPS fetch + JSON parse
        │   └── WidgetPrefs.kt                   # SharedPreferences helper
        └── res/
            ├── layout/widget_bitcoin_price.xml  # The widget itself
            ├── layout/activity_widget_config.xml# Currency picker UI
            ├── xml/bitcoin_price_widget_info.xml# Widget metadata (size, refresh)
            ├── drawable/ic_bitcoin.xml          # Bitcoin logo (vector)
            ├── drawable/widget_background.xml   # Rounded background
            ├── drawable/ic_launcher_*.xml       # Adaptive launcher icon
            ├── values/strings.xml
            ├── values/colors.xml
            ├── values/themes.xml
            └── values-night/colors.xml          # Dark-mode colors
```

---

## How the price is fetched

`PriceFetcher.fetchPrice("USD")` does a plain `HttpURLConnection` GET on
`https://cheeserobot.org/price/latest.json` and walks the resulting JSON
recursively for a key matching the requested currency (case-insensitive).
That keeps the parser robust whether the API returns
`{"usd": 65000, "eur": 60000}`, `{"prices": {...}}`, or a Coingecko-style
`{"bitcoin": {...}}` shape. The value is rounded to the nearest whole
number with locale-appropriate thousands separators.

If the network call fails the widget shows `!`, posts a system notification
with the actual error (network exception, HTTP status + body snippet, JSON
parse failure, or "currency key not found, top-level keys were …"), and the
user can tap the notification (or the widget) to retry immediately.

---

## Debugging a stuck widget (developer)

If you're seeing `!` or a flat `…`, the fastest way to find out why is
logcat. Every error path also logs under the `CheeseBTC` tag:

```bash
adb logcat -s CheeseBTC:V
```

Common causes:

- **`POST_NOTIFICATIONS not granted`** on Android 13+ — the system
  notification won't appear, but the price-fetch error itself is a
  separate problem; check the lines above it.
- **`Network: UnknownHostException`** — the device has no internet, or
  DNS is blocking the host.
- **`HTTP 4xx/5xx`** — the API endpoint changed or is down. The snippet
  in the message is the first ~160 chars of the response body.
- **`Bad JSON: …`** — the endpoint returned HTML (e.g. a captive portal
  or a Cloudflare page) instead of JSON.
- **`No "USD" in JSON. Keys: …`** — the JSON parsed but doesn't contain
  the requested currency anywhere. The listed top-level keys tell you
  what shape it actually has; tweak `PriceFetcher.findCurrency` if the
  schema uses something exotic like `{"usd_price": …}`.

---

## Things you might want to change (developer)

- **Refresh rate**: edit `android:updatePeriodMillis` in
  `bitcoin_price_widget_info.xml`. 30 minutes is the system minimum; values
  smaller than that are silently clamped. For tighter refresh use
  `WorkManager` with a periodic worker.
- **Colors / shape**: edit `widget_background.xml` and `colors.xml`
  (incl. `values-night/colors.xml` for dark mode).
- **Currencies**: add new symbols in `WidgetPrefs.symbolFor` and add a
  matching button in `activity_widget_config.xml` + `WidgetConfigActivity`.
- **Decimals**: `BitcoinPriceWidgetProvider.formatWhole` rounds to a
  whole number. Swap for `NumberFormat.getCurrencyInstance(...)` if you
  want a localized "$65,432.10".

---

## Publishing to F-Droid

The repo already contains everything F-Droid expects:

- `LICENSE` — MIT
- `CHANGELOG.md`
- `fastlane/metadata/android/en-US/` — title, short and full description,
  per-`versionCode` changelog. F-Droid scrapes this directory for the
  listing on f-droid.org.
- `fdroid/metadata/org.cheeserobot.btcwidget.yml` — the build recipe
  template you'll paste into your `fdroiddata` fork.

Steps to actually get listed on f-droid.org:

1. Make sure the version you want shipped is tagged. F-Droid builds from
   tags, not arbitrary commits:

   ```bash
   git tag v1.0
   git push origin v1.0
   ```

2. Fork <https://gitlab.com/fdroid/fdroiddata>.

3. Copy the contents of `fdroid/metadata/org.cheeserobot.btcwidget.yml`
   into `metadata/org.cheeserobot.btcwidget.yml` inside your fork (path
   matches the `applicationId`). Adjust if anything has changed.

4. Run `fdroid lint org.cheeserobot.btcwidget` locally if you have the
   `fdroidserver` tools installed (optional but speeds review). Fix any
   warnings.

5. Open a merge request against the upstream `fdroiddata` repo titled
   *"New app: org.cheeserobot.btcwidget"*. Reference any tracking issue
   if there is one.

6. Wait. F-Droid review queue is typically a few weeks. The reviewers
   will leave comments on the MR — usually small things like description
   length, anti-feature disclosure, or build server compatibility.

### Build server compatibility

F-Droid's reproducible-build server pins specific Android Gradle Plugin
versions. AGP and Gradle move faster than the buildserver does. As of
this project's tagging, the local build is on AGP 9.x / Gradle 9.2 — if
the buildserver hasn't caught up, the reviewer will ask you to ship the
F-Droid build off a branch pinned to the supported AGP. The cleanest way
is to keep `main` on whatever Android Studio recommends and maintain a
`fdroid` branch with `build.gradle.kts` pinned to a known-good AGP, then
point the YAML's `commit:` at the tag on that branch.

Check the current supported versions at
<https://gitlab.com/fdroid/fdroidserver/-/blob/master/buildserver/Vagrantfile>
or in `fdroidserver`'s release notes before each release.

### Anti-feature disclosure

The app contacts exactly one host: `cheeserobot.org`. That isn't an
F-Droid anti-feature, but the description should mention it (already
done in `fastlane/.../full_description.txt`). If the reviewer flags
anything else, update both the YAML and the fastlane description.
