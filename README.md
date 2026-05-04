# Cheese BTC Widget

A small Android home-screen widget that shows the latest Bitcoin price from
[`https://cheeserobot.org/price/latest.json`](https://cheeserobot.org/price/latest.json).

When you add the widget you pick **USD** or **EUR**; the widget then shows
the Bitcoin logo, the currency symbol (`$` or `€`) and the price rounded to a
whole number. Tap the widget to force a refresh; otherwise it auto-refreshes
about every 30 minutes (the Android-imposed minimum for this kind of widget).

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

## Build & install

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

## Adding the widget on the device

1. Long-press an empty spot on the home screen → **Widgets**.
2. Find **Cheese BTC Widget** in the list, drag **BTC Price** onto the
   home screen.
3. The picker opens — tap **US Dollars ($)** or **Euros (€)**.
4. The widget loads with the current price.

You can place the widget multiple times with different currencies.

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

## Debugging a stuck widget

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

## Things you might want to change

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
