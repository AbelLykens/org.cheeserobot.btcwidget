// Top-level build file. AGP 9 ships built-in Kotlin support, so we
// don't apply the standalone `org.jetbrains.kotlin.android` plugin
// here — applying both causes a "kotlin extension already registered"
// collision at configure time.
plugins {
    id("com.android.application") version "9.0.1" apply false
}
