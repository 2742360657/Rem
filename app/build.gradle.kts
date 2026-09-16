import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseSigningPropertiesFile = providers.gradleProperty("rem.signingProperties")
    .orElse(providers.environmentVariable("REM_SIGNING_PROPERTIES"))
    .orElse("T:/jks/keystore.properties")
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
        versionCode = 3
        versionName = "0.0.3"

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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * Keeps the R8 mapping next to the released APK.
 *
 * The mapping is what turns an obfuscated stack trace from a user's device back into
 * readable class and method names. It is regenerated inside `build/`, which is cleaned
 * routinely, so the copy under `dist/` is the one that survives long enough to be useful.
 */
val archiveReleaseMapping by tasks.registering(Copy::class) {
    description = "Archives the release R8 mapping for deobfuscating user crash reports."
    val mappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    from(mappingFile)
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "Rem-${android.defaultConfig.versionName}-mapping.txt" }
    onlyIf { mappingFile.get().asFile.isFile }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(archiveReleaseMapping)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-gif:3.2.0")
    implementation("io.coil-kt.coil3:coil-svg:3.2.0")
    implementation("io.coil-kt.coil3:coil-video:3.2.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
