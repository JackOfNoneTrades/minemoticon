/// START FENT PREF
import net.darkhax.curseforgegradle.TaskPublishCurseForge
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.provider.ListProperty
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import java.util.zip.ZipFile

val fentMavenName = "Fent Maven"
val fentMavenUrl = uri("https://maven.fentanylsolutions.org/releases")
val jsvgVersion = providers.gradleProperty("jsvgVersion").orElse("2.1.0").get()
val configuredModGroup = providers.gradleProperty("modGroup").get()
val configuredModId = providers.gradleProperty("modId").get()
val configuredModName = providers.gradleProperty("modName").get()

// addon.gradle.kts is evaluated before dependencies.gradle, so create these
// custom configurations here and let dependencies.gradle populate them later.
val depLoaderBootstrap = configurations.maybeCreate("deploader")
val fatImplementation = configurations.maybeCreate("fatImplementation")

fun RepositoryHandler.keepFentMavenFirst() {
    fun currentFentRepo(): MavenArtifactRepository? = withType(MavenArtifactRepository::class.java)
        .firstOrNull { it.url == fentMavenUrl || it.name == fentMavenName }

    fun promoteFentRepo() {
        val fentRepo = currentFentRepo() ?: maven {
            name = fentMavenName
            url = fentMavenUrl
        }
        if (firstOrNull() !== fentRepo) {
            remove(fentRepo)
            addFirst(fentRepo)
        }
    }

    promoteFentRepo()
    whenObjectAdded {
        promoteFentRepo()
    }
}

gradle.allprojects {
    repositories.keepFentMavenFirst()
    buildscript.repositories.keepFentMavenFirst()
}
/// END FENT PREF

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

tasks.named<ProcessResources>("processResources").configure {
    dependsOn("buildFreetypeNatives")
    from(generatedNativeResourcesRoot)

    notCompatibleWithConfigurationCache("Expands dependency versions and embeds the DepLoader bootstrap.")
    inputs.property("jsvgVersion", jsvgVersion)
    filesMatching("META-INF/minemoticon_dependencies.json") {
        expand("jsvgVersion" to jsvgVersion)
    }
    // Only DepLoader's small bootstrap is embedded. FalsePatternLib itself is
    // neither bundled nor required as an installed mod.
    from(depLoaderBootstrap) {
        rename { "fplib_deploader.jar" }
    }
}

tasks.named("jar").configure {
    dependsOn("buildFreetypeNatives")
}

val baseJar = tasks.named<Jar>("jar")
val reobfJar = tasks.named<Jar>("reobfJar")
val shadedDevJar = tasks.named<Jar>("shadowJar")

// Keep self-contained jars as the default artifacts. DepLoader jars remain
// available behind explicit slim classifiers for users who prefer them.
reobfJar.configure {
    archiveClassifier.set("slim")
}
shadedDevJar.configure {
    archiveClassifier.set("slim-dev")
}

fun Jar.configureFatJar(sourceJar: TaskProvider<out Jar>, classifier: String) {
    group = "build"
    description = if (classifier.isEmpty()) {
        "Builds the fully bundled default jar."
    } else {
        "Builds a fully bundled $classifier jar."
    }
    notCompatibleWithConfigurationCache("Unpacks runtime dependencies into the fat distribution jar.")

    dependsOn(sourceJar)

    archiveBaseName.set(baseJar.flatMap { it.archiveBaseName })
    archiveVersion.set(baseJar.flatMap { it.archiveVersion })
    archiveClassifier.set(classifier)
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceJar.flatMap { it.archiveFile }.map { zipTree(it) }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/minemoticon_dependencies.json",
        )
    }
    from(providers.provider { fatImplementation.map(::zipTree) }) {
        exclude(
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/*.SF",
            "META-INF/MANIFEST.MF",
            "META-INF/versions/**/module-info.class",
        )
    }

    manifest {
        attributes(
            "FMLCorePluginContainsFMLMod" to "true",
            "FMLCorePlugin" to "$configuredModGroup.core.EarlyMixinLoader",
            "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
            "MixinConfigs" to "mixins.$configuredModId.json",
            "ForceLoadAsMod" to "true",
        )
    }
}

val fatJar = tasks.register<Jar>("fatJar") {
    configureFatJar(reobfJar, "")
}

val fatDevJar = tasks.register<Jar>("fatDevJar") {
    configureFatJar(shadedDevJar, "dev")
}

tasks.named("assemble").configure {
    dependsOn(fatJar, fatDevJar)
}

fun replaceOutgoingJar(configurationName: String, jarTask: TaskProvider<out Jar>) {
    configurations.named(configurationName).configure {
        outgoing.artifacts.clear()
        outgoing.artifact(jarTask)
    }
}

replaceOutgoingJar("apiElements", fatDevJar)
replaceOutgoingJar("runtimeElements", fatDevJar)
replaceOutgoingJar("reobfElements", fatJar)

plugins.withId("maven-publish") {
    extensions.getByType(PublishingExtension::class.java)
        .publications
        .withType(MavenPublication::class.java)
        .configureEach {
            artifact(reobfJar)
            artifact(shadedDevJar)
        }
}

val verifyDistributionJars = tasks.register("verifyDistributionJars") {
    group = "verification"
    description = "Verifies default jars are bundled and slim jars retain DepLoader metadata."
    notCompatibleWithConfigurationCache("Scans distribution jar contents.")
    dependsOn(fatJar, fatDevJar, reobfJar, shadedDevJar)
    inputs.files(
        fatJar.flatMap { it.archiveFile },
        fatDevJar.flatMap { it.archiveFile },
        reobfJar.flatMap { it.archiveFile },
        shadedDevJar.flatMap { it.archiveFile },
    )

    doLast {
        val dependencyDescriptor = "META-INF/minemoticon_dependencies.json"
        val bundledEntry = "com/github/weisj/jsvg/SVGDocument.class"

        fun entriesOf(archive: File): Set<String> = ZipFile(archive).use { zip ->
            buildSet {
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    add(entries.nextElement().name)
                }
            }
        }

        listOf(fatJar.get(), fatDevJar.get()).forEach { task ->
            val archive = task.archiveFile.get().asFile
            val entries = entriesOf(archive)
            check(bundledEntry in entries) { "Bundled archive ${archive.name} is missing $bundledEntry" }
            check(dependencyDescriptor !in entries) {
                "Bundled archive ${archive.name} still contains $dependencyDescriptor"
            }
        }

        listOf(reobfJar.get(), shadedDevJar.get()).forEach { task ->
            val archive = task.archiveFile.get().asFile
            val entries = entriesOf(archive)
            check(dependencyDescriptor in entries) {
                "Slim archive ${archive.name} is missing $dependencyDescriptor"
            }
            check(bundledEntry !in entries) { "Slim archive ${archive.name} unexpectedly contains $bundledEntry" }
        }
    }
}

tasks.named("check").configure {
    dependsOn(verifyDistributionJars)
}

// Publish the bundled jar as the primary file and the DepLoader jar as an
// explicitly named slim alternative.
fun Any.callNoArg(methodName: String): Any? = javaClass.methods
    .first { it.name == methodName && it.parameterCount == 0 }
    .invoke(this)

plugins.withId("com.modrinth.minotaur") {
    afterEvaluate {
        @Suppress("UNCHECKED_CAST")
        val additionalFiles = extensions.findByName("modrinth")
            ?.callNoArg("getAdditionalFiles") as? ListProperty<Any>
        additionalFiles?.add(reobfJar)
    }
    tasks.matching { it.name == "modrinth" }.configureEach {
        dependsOn(fatJar, reobfJar)
    }
}

plugins.withId("net.darkhax.curseforgegradle") {
    afterEvaluate {
        tasks.named<TaskPublishCurseForge>("publishCurseforge").configure {
            dependsOn(fatJar, reobfJar)
            uploadArtifacts.firstOrNull()?.let { artifact ->
                artifact.addEnvironment("Client", "Server")
                artifact.withAdditionalFile(reobfJar).displayName = providers.provider {
                    "$configuredModName ${project.version}-slim"
                }
            }
        }
    }
}

extensions.extraProperties.set("publishableObfJar", fatJar)
extensions.extraProperties.set("publishableDevJar", fatDevJar)
extensions.extraProperties.set("publishableFatJar", fatJar)
afterEvaluate {
    // 67minecraft's additional-file hook.
    val extras = extensions.extraProperties
    extras.set("publishableApiJar", reobfJar)
}

tasks.matching { it.name == "publish67Minecraft" }.configureEach {
    dependsOn(fatJar, fatDevJar, reobfJar)
}
