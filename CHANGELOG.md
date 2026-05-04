# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
