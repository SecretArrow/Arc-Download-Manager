import java.time.Duration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.arc.downloadmanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arc.downloadmanager"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "2.2.0"

        testInstrumentationRunner = "com.arc.downloadmanager.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val storeFileEnv = System.getenv("ARC_STORE_FILE")
            if (!storeFileEnv.isNullOrBlank()) {
                storeFile = file(storeFileEnv)
                storePassword = System.getenv("ARC_STORE_PASSWORD")
                keyAlias = System.getenv("ARC_KEY_ALIAS")
                keyPassword = System.getenv("ARC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val envSigning = !System.getenv("ARC_STORE_FILE").isNullOrBlank()
            signingConfig = if (envSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf("-opt-in=kotlin.RequiresOptIn")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
    }
}

tasks.withType<Test> {
    // Convert hangs into diagnosable failures instead of burning the whole job timeout.
    timeout.set(Duration.ofMinutes(8))
    testLogging {
        events("started", "failed", "skipped")
        showExceptions = true
        showStackTraces = true
        showCauses = true
    }
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Work
    implementation(libs.androidx.work.runtime)

    // Network
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Extra protocols: FTP/FTPS (Apache Commons Net) and SFTP (JSch)
    implementation(libs.commons.net)
    implementation(libs.jsch)

    // BitTorrent (magnet links) via jlibtorrent - native libtorrent 2.0.
    // ABIs: arm64-v8a + armeabi-v7a + x86_64 (32-bit x86 is a dead platform; devices with
    // exotic ABIs degrade gracefully - only torrent tasks fail, every other protocol works).
    implementation(libs.jlibtorrent)
    implementation(libs.jlibtorrent.arm)
    implementation(libs.jlibtorrent.arm64)
    implementation(libs.jlibtorrent.x64)

    // Files / media
    implementation(libs.androidx.documentfile)
    implementation(libs.coil.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.hilt.android)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
