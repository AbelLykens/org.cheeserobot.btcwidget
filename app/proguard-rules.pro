# Project ProGuard rules.

-keepattributes Signature
-keepattributes *Annotation*

# AppWidgetProvider is invoked by the framework via reflection through the
# manifest. Keep our entry-point classes so R8 doesn't rename or drop them.
-keep class org.cheeserobot.btcwidget.BitcoinPriceWidgetProvider { *; }
-keep class org.cheeserobot.btcwidget.WidgetConfigActivity { *; }
-keep class org.cheeserobot.btcwidget.LauncherActivity { *; }
-keep class org.cheeserobot.btcwidget.BatterySaverInfoActivity { *; }

# Coroutines helpers reflectively probe DebugProbesKt; we already strip the
# .bin file from the APK, but tell R8 it's fine if it's missing too.
-dontwarn kotlinx.coroutines.debug.**
