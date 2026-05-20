import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
val releaseStoreFile: String? = localProps.getProperty("releaseStoreFile")
val releaseStorePassword: String? = localProps.getProperty("releaseStorePassword")
val releaseKeyAlias: String? = localProps.getProperty("releaseKeyAlias")
val releaseKeyPassword: String? = localProps.getProperty("releaseKeyPassword")

/**
 * Short HEAD commit hash, baked into BuildConfig so the settings screen
 * can show users which build they're on. Returns "unknown" outside a
 * git tree (e.g. when building from a source tarball).
 */
fun gitCommitShort(): String =
    runCatching {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readText().trim()
    }.getOrDefault("unknown")

android {
    namespace = "dev.btclock.widget"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.btclock.widget"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommitShort()}\"")
    }

    signingConfigs {
        if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Glance widget runtime
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Background refresh
    implementation(libs.work.runtime.ktx)

    // Backend host preference
    implementation(libs.datastore.preferences)

    // Settings UI
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Networking
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
