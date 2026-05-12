plugins {
    id("com.android.application")
    // Kotlin support is provided by AGP 9's built-in integration —
    // applying id("org.jetbrains.kotlin.android") on top of that
    // causes a "kotlin extension already registered" collision.
}

android {
    namespace = "org.cheeserobot.btcwidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.cheeserobot.btcwidget"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // We only ship English strings — drop every other locale's
        // resources from the APK (each translated `values-*` folder pulls
        // in ~kB of strings from appcompat/etc.).
        resourceConfigurations += listOf("en")

        // Vector drawables are supported natively from API 21; minSdk is
        // 26 so the support-library shim is unnecessary (and would
        // require us to depend on appcompat).
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Returns 0/null/false from stubbed Android APIs in unit tests
        // instead of throwing. Lets us hit error paths that incidentally
        // call android.util.Log.* without needing to mock them.
        unitTests.isReturnDefaultValues = true
    }

    // Disable build artifacts we don't use — each one adds resources
    // and/or generated classes to the final APK.
    buildFeatures {
        buildConfig = false
        resValues = false
        shaders = false
    }

    buildTypes {
        debug {
            // Keep debug builds fast; no shrinking.
            isMinifyEnabled = false
        }
        release {
            // R8 strips unused Kotlin/AndroidX classes; resource shrinker
            // drops the hundreds of `abc_*.png` and `mtrl_*.png` assets
            // pulled in transitively by appcompat/material that we don't
            // reference.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Don't embed git revision into META-INF/version-control-info.textproto.
            // AGP enables this by default, which writes the build-time commit SHA
            // into the APK and breaks F-Droid's reproducible-build verification
            // any time someone builds from a different commit than F-Droid does.
            vcsInfo {
                include = false
            }
        }
    }

    // Drop AGP-injected dependency metadata we don't ship to a store.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "kotlin/**",
                "DebugProbesKt.bin"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9's built-in Kotlin DSL. Replaces the legacy
    // `kotlinOptions { jvmTarget = "17" }` block, which only existed
    // when the standalone `org.jetbrains.kotlin.android` plugin was
    // applied.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // Keep dependencies minimal. We deliberately avoid appcompat and
    // material — the widget config UI is just three stock framework
    // widgets and pulling either of those libraries in adds megabytes of
    // PNG/9-patch drawables (abc_btn_switch_to_on_mtrl_*.png, etc.) that
    // bloat the APK without us using them.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Unit tests (JVM, run via :app:test)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")

    // Instrumented tests (run on device, via :app:connectedAndroidTest)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
