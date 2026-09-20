import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseSigningPropertiesFile = providers.gradleProperty("rem.signingProperties")
    .orElse(providers.environmentVariable("REM_SIGNING_PROPERTIES"))
    .orElse("jks/keystore.properties")
    .map(::file)
    .get()
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}
val hasReleaseSigning = releaseSigningPropertiesFile.isFile

android {
    namespace = "dev.susnowy.gallery"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.susnowy.rem"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningProperties.getProperty("storeFile")) {
                    "${releaseSigningPropertiesFile.path} 缺少 storeFile"
                })
                storePassword = requireNotNull(releaseSigningProperties.getProperty("storePassword")) {
                    "${releaseSigningPropertiesFile.path} 缺少 storePassword"
                }
                keyAlias = requireNotNull(releaseSigningProperties.getProperty("keyAlias")) {
                    "${releaseSigningPropertiesFile.path} 缺少 keyAlias"
                }
                keyPassword = requireNotNull(releaseSigningProperties.getProperty("keyPassword")) {
                    "${releaseSigningPropertiesFile.path} 缺少 keyPassword"
                }
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/**
 * Ships `AGENT_LIBRARY_RULES.md` to the Library as `.gallery/RULES.md`.
 *
 * The document is copied from the repository root rather than kept as a second file under
 * `assets/`, because a hand-maintained duplicate is exactly the thing that goes stale. Attaching a
 * Library overwrites its copy, so the spec on disk always matches the build the user is running.
 */
val syncLibraryRules by tasks.registering(Copy::class) {
    description = "Copies the Library file-format rules into the app assets."
    from(rootProject.layout.projectDirectory.file("AGENT_LIBRARY_RULES.md"))
    into(layout.projectDirectory.dir("src/main/assets"))
    // The asset name is what LibraryStore reads; the repository keeps the descriptive name.
    rename { "RULES.md" }
}

tasks.named("preBuild") { dependsOn(syncLibraryRules) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")

    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Thumbnails, and the built-in viewer's images.
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-gif:3.2.0")
    implementation("io.coil-kt.coil3:coil-video:3.2.0")

    // Built-in viewer. Both versions were measured on device in the throwaway probe project
    // before being adopted here; see docs/STATUS.md for the format results.
    // Telephoto 0.19.0 pulls Coil 3.2.0 and Compose runtime 1.8.0, which is this project's line.
    implementation("me.saket.telephoto:zoomable-image-coil3:0.19.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
