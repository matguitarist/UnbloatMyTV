import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// One version for every build: it comes from the Windows project, like the Mac app's.
val appVersion: String = Regex("<Version>(.*)</Version>")
    .find(rootDir.resolve("../src/AndroidTVDebloater/AndroidTVDebloater.csproj").readText())!!.groupValues[1]

// Release signing comes from keystore.properties (or the CI's environment); without it, release
// builds are signed with the debug key so they still install.
val signing = Properties().apply {
    rootDir.resolve("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun secret(name: String): String? = System.getenv(name) ?: signing.getProperty(name)

android {
    namespace = "io.github.matguitarist.unbloatmytv"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.matguitarist.unbloatmytv"
        minSdk = 26
        targetSdk = 36
        val (major, minor, patch) = appVersion.split('.').map(String::toInt)
        versionCode = major * 10000 + minor * 100 + patch
        versionName = appVersion
        // Phones and tablets are ARM; x86_64 covers Chromebooks and emulators. 32-bit x86 phones are long gone.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        if (secret("ANDROID_KEYSTORE") != null) create("release") {
            storeFile = file(secret("ANDROID_KEYSTORE")!!)
            storePassword = secret("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = secret("ANDROID_KEY_ALIAS")
            keyPassword = secret("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The catalog shared with the desktop apps, built in as the first copy (a newer one comes from GitHub).
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/catalog"))

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // BouncyCastle's post-quantum tables and certificate-path messages: never used here.
        resources.excludes += listOf("org/bouncycastle/pqc/**", "org/bouncycastle/x509/*.properties")
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

val copyCatalog by tasks.registering(Copy::class) {
    from(rootDir.resolve("../shared/catalog.json"))
    // Reads app names and pictures on the TV for My apps; the desktop apps use the same file.
    from(rootDir.resolve("../helper/umtv-helper.dex"))
    into(layout.buildDirectory.dir("generated/catalog"))
}
tasks.named("preBuild") { dependsOn(copyCatalog) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.dadb)
    // Wireless debugging with a pairing code (TLS), for TVs without network debugging on port 5555.
    implementation(libs.libadb)
    implementation(libs.conscrypt)
    // The same one libadb uses, to wrap the ADB key in the certificate TLS asks for.
    implementation(libs.bouncycastle)
}
