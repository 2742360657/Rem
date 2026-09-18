plugins {
    id("com.android.application") version "8.12.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
}

// Both Android and the standalone tool ship the same reviewed, offline instructions.
val prepareLibraryAgentResources by tasks.registering {
    val sources = files("docs/LIBRARY_AGENT.md", "docs/PORTABLE_FORMAT.md")
    val destination = layout.buildDirectory.dir("generated/library-agent-resources")
    inputs.files(sources)
    outputs.dir(destination)
    doLast {
        val output = destination.get().file("rem-library-agent.md").asFile
        output.parentFile.mkdirs()
        output.writeText(sources.files.joinToString("\n\n---\n\n") { source ->
            // A Library must not depend on repository-relative Markdown links.
            source.readText().replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        }, Charsets.UTF_8)
    }
}
