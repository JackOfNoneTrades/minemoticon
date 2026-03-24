tasks.withType<JavaExec>().configureEach {
    if (name.startsWith("runServer")) {
        // WawelAuth GUI stack is client-only. Strip these from dedicated-server
        // runtime right before launch (GTNH setup appends classpath later).
        doFirst("wawelauthStripClientOnlyMods") {
            classpath = classpath.filter { file ->
                val n = file.name
                !n.contains("ModularUI2", ignoreCase = true) && !n.contains("Baubles-Expanded", ignoreCase = true) && !n.contains("angelica", ignoreCase = true)
            }
        }
    }
}

tasks.register<JavaExec>("renderEmojiFontHeadless") {
    group = "verification"
    description = "Render emoji glyphs to PNG without launching Minecraft."
    notCompatibleWithConfigurationCache("Arguments are derived from project properties at execution time.")
    dependsOn("classes")
    classpath = files(
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("resources/main"),
        configurations.getByName("runtimeClasspath"))
    mainClass.set("org.fentanylsolutions.minemoticon.tools.HeadlessFontRenderTool")
    jvmArgs("-Djava.awt.headless=true")

    doFirst {
        val fontPath = (findProperty("fontPath") as String?)
            ?: "${project.projectDir}/run/client/config/minemoticon/fonts/NotoColorEmoji-Regular.ttf"
        val outputDir = (findProperty("renderOut") as String?)
            ?: "${layout.buildDirectory.get().asFile}/headless-font-render"
        val emojiSample = findProperty("emojiSample") as String?

        args = if (emojiSample != null) {
            listOf(fontPath, outputDir, emojiSample)
        } else {
            listOf(fontPath, outputDir)
        }
    }
}
