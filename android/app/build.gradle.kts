import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load keystore properties if available
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.remotephone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.remotephone"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "2.0.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The browser client (../web) ships inside the APK, served by WebClientServer
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webclient"))

    dependenciesInfo {
        // Disable dependency metadata in APK signing block — F-Droid scanner rejects it
        includeInApk = false
        includeInBundle = false
    }
}

val copyWebClient by tasks.registering(Sync::class) {
    from(rootProject.file("../web")) { include("index.html", "remotephone.js", "sw.js", "manifest.webmanifest", "*.png") }
    into(layout.buildDirectory.dir("generated/webclient/web"))
}
tasks.named("preBuild") { dependsOn(copyWebClient) }
// Every task reading assets must run after the copy (Gradle rejects implicit dependencies)
tasks.configureEach {
    if (name != "copyWebClient" && (name.contains("Assets") || name.startsWith("lint"))) dependsOn(copyWebClient)
}

dependencies {
    implementation("androidx.core:core:1.12.0")          // NotificationCompat
    implementation("androidx.activity:activity:1.8.2")  // ComponentActivity + registerForActivityResult
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    // Optional desktop mode (DeX / Android desktop): a shell-privileged display via Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
