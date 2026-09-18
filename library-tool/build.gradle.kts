plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

kotlin { jvmToolchain(17) }

// Compile shared source, never a fork of the Android portable format.
kotlin.sourceSets.main {
    kotlin.srcDir("../app/src/main/java")
    kotlin.include("dev/susnowy/gallery/model/MediaModels.kt")
    kotlin.include("dev/susnowy/gallery/model/LibraryModels.kt")
    kotlin.include("dev/susnowy/gallery/model/InboxModels.kt")
    kotlin.include("dev/susnowy/gallery/portable/**")
    kotlin.include("dev/susnowy/gallery/tool/**")
}
sourceSets.main { resources.srcDir(rootProject.layout.buildDirectory.dir("generated/library-agent-resources")) }
tasks.processResources { dependsOn(rootProject.tasks.named("prepareLibraryAgentResources")) }
application { mainClass.set("dev.susnowy.gallery.tool.MainKt") }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
}
