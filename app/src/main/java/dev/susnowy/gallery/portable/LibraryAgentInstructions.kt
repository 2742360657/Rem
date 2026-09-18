package dev.susnowy.gallery.portable

/** Built from reviewed contracts; shipped in APK and desktop distribution. */
object LibraryAgentInstructions {
    fun text(): String = checkNotNull(
        LibraryAgentInstructions::class.java.getResourceAsStream("/rem-library-agent.md"),
    ) { "缺少 Library Agent 说明资源" }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
