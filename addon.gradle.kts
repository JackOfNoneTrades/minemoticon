tasks.withType<JavaExec>().configureEach {
    if (name.startsWith("run")) {
        dependsOn("buildFreetypeNatives")
    }

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

extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
    .named("main") {
        java.srcDir("native/freetype-jni/freetype-jni")
    }

val configuredFreetypeVersion = providers.provider {
    (findProperty("freetypeVersion") as String?) ?: "2.14.3"
}
val configuredFreetypeTag = configuredFreetypeVersion.map { version ->
    "VER-" + version.replace('.', '-')
}
val configuredZigVersion = providers.provider {
    (findProperty("zigVersion") as String?) ?: "0.13.0"
}
val defaultNativeTargets = providers.provider {
    if (gradle.startParameter.taskNames.any { taskName ->
            taskName == "build" || taskName == "assemble" || taskName == "jar" || taskName == "shadowJar" || taskName.endsWith("Jar") || taskName.startsWith("publish")
        }) {
        "all"
    } else {
        "host"
    }
}
val configuredNativeTargets = providers.gradleProperty("nativeTargets")
    .orElse(defaultNativeTargets)
val nativeBuildScript = layout.projectDirectory.file("native/build-zig.sh")
    .asFile.absolutePath
val freetypeSyncScript = layout.projectDirectory.file("native/sync-freetype.sh")
    .asFile.absolutePath
val localZigRoot = layout.projectDirectory.dir("native/toolchains/zig")
val freetypeSubmoduleRoot = layout.projectDirectory.dir("native/freetype")
val generatedNativeResourcesRoot = layout.buildDirectory.dir("generated/freetype-resources")
val generatedBundledNativeResources = generatedNativeResourcesRoot.map { it.dir("natives") }

tasks.register<Exec>("setupLocalZig") {
    group = "build setup"
    description = "Download a project-local Zig toolchain for native builds."
    workingDir = project.projectDir
    environment("ZIG_VERSION", configuredZigVersion.get())
    commandLine("bash", nativeBuildScript, "--setup-only")
    inputs.file(nativeBuildScript)
    inputs.property("zigVersion", configuredZigVersion)
    outputs.dir(localZigRoot)
}

tasks.register<Exec>("syncFreetypeSubmodule") {
    group = "build setup"
    description = "Initialize the FreeType submodule and check out the configured release tag."
    workingDir = project.projectDir
    environment("FREETYPE_VERSION", configuredFreetypeVersion.get())
    environment("FREETYPE_TAG", configuredFreetypeTag.get())
    commandLine("bash", freetypeSyncScript, "--sync")
    inputs.file(freetypeSyncScript)
    inputs.file(layout.projectDirectory.file(".gitmodules"))
    inputs.property("freetypeVersion", configuredFreetypeVersion)
    outputs.upToDateWhen { false }
}

tasks.register<Exec>("ensureFreetypeSubmodule") {
    group = "build setup"
    description = "Initialize the FreeType submodule and verify it matches the configured release tag."
    workingDir = project.projectDir
    environment("FREETYPE_VERSION", configuredFreetypeVersion.get())
    environment("FREETYPE_TAG", configuredFreetypeTag.get())
    commandLine("bash", freetypeSyncScript, "--verify")
    inputs.file(freetypeSyncScript)
    inputs.file(layout.projectDirectory.file(".gitmodules"))
    inputs.property("freetypeVersion", configuredFreetypeVersion)
    outputs.upToDateWhen { false }
}

tasks.register<Exec>("buildFreetypeNatives") {
    group = "build"
    description = "Build bundled FreeType JNI natives for all supported platforms using the local Zig toolchain."
    workingDir = project.projectDir
    dependsOn("setupLocalZig")
    dependsOn("ensureFreetypeSubmodule")
    environment("FREETYPE_VERSION", configuredFreetypeVersion.get())
    environment("FREETYPE_TAG", configuredFreetypeTag.get())
    environment("ZIG_VERSION", configuredZigVersion.get())
    environment("NATIVE_RESOURCE_DIR", generatedBundledNativeResources.get().asFile.absolutePath)
    commandLine("bash", nativeBuildScript, configuredNativeTargets.get())
    inputs.file(nativeBuildScript)
    inputs.file(freetypeSyncScript)
    inputs.file(layout.projectDirectory.file(".gitmodules"))
    inputs.file(layout.projectDirectory.file("native/freetype-jni/build.zig"))
    inputs.file(layout.projectDirectory.file("native/freetype-jni/jni/freetype_jni.c"))
    inputs.dir(layout.projectDirectory.dir("native/freetype-jni/freetype-jni"))
    inputs.dir(freetypeSubmoduleRoot)
    inputs.dir(layout.projectDirectory.dir("native/jni-headers"))
    inputs.property("freetypeVersion", configuredFreetypeVersion)
    inputs.property("zigVersion", configuredZigVersion)
    inputs.property("nativeTargets", configuredNativeTargets)
    outputs.dir(generatedNativeResourcesRoot)
}

tasks.named("processResources", Copy::class).configure {
    dependsOn("buildFreetypeNatives")
    from(generatedNativeResourcesRoot)
}

tasks.named("jar").configure {
    dependsOn("buildFreetypeNatives")
}
