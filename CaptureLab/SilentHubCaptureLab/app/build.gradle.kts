plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull

// Decode the repository's text-encoded launcher artwork into generated Android resources.
// This keeps the actual PNG resources reproducible without committing binary blobs through the
// GitHub text-only file editor.
val generatedLauncherResDir = layout.buildDirectory.dir("generated/res/silenthubLauncher")
val launcherAssetDir = layout.projectDirectory.dir("launcher-assets")
val generateSilentHubLauncherAssets by tasks.registering {
    inputs.dir(launcherAssetDir)
    outputs.dir(generatedLauncherResDir)
    doLast {
        val output = generatedLauncherResDir.get().dir("mipmap-nodpi").asFile
        output.mkdirs()
        val decoder = java.util.Base64.getMimeDecoder()
        val foreground = launcherAssetDir.file("silenthub_adaptive_foreground.png.b64").asFile
            .readText().trim()
        val legacy = launcherAssetDir.file("silenthub_legacy_icon.png.b64").asFile
            .readText().trim()
        output.resolve("ic_launcher_foreground.png")
            .writeBytes(decoder.decode(foreground))
        output.resolve("ic_launcher.png")
            .writeBytes(decoder.decode(legacy))
    }
}

android {
    sourceSets.getByName("main").res.srcDir(generatedLauncherResDir)

    namespace = "com.silenthub.capturelab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.silenthub.capturelab.live"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("appVersionCode").orElse("5").get().toInt()
        versionName = providers.gradleProperty("appVersionName").orElse("1.0.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (!releaseStorePath.isNullOrBlank()) {
                storeFile = file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateSilentHubLauncherAssets)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    testImplementation("junit:junit:4.13.2")
}