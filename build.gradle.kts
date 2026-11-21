import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipFile

plugins {
    java
    `maven-publish`
    alias(libs.plugins.shadow)
}

group = "gg.moonrise.hawkeye"
description = "Hawkeye Paper plugin"

// Versions are year.quarter (docs/knowledge/README.md "Release facts"), and paper-plugin.yml, /he help and the
// startup line all show this one string:
//   stable release   26.4      -Pversion from the GitHub release tag (CI); a second release that quarter is 26.4.1
//   Jenkins build    26.4.B25  the quarter of the commit plus Jenkins' BUILD_NUMBER
//   any other build  26.4-SNAPSHOT
// The quarter comes from the commit date (UTC), so rebuilding an old commit gives it the same version.
val versionPattern = Regex("""\d{2}\.[1-4](\.\d+|\.B\d+|-SNAPSHOT)?""")
if (version == Project.DEFAULT_VERSION) {
    val commitDate = providers.exec {
        commandLine("git", "log", "-1", "--format=%cd", "--date=format-local:%Y-%m-%d")
        environment("TZ", "UTC")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    val date = if (commitDate.isEmpty()) LocalDate.now(ZoneOffset.UTC) else LocalDate.parse(commitDate)
    val quarter = "%02d.%d".format(date.year % 100, (date.monthValue - 1) / 3 + 1)
    val jenkinsBuild = providers.environmentVariable("BUILD_NUMBER").orNull?.takeIf { it.isNotBlank() }
    version = if (jenkinsBuild != null) "$quarter.B$jenkinsBuild" else "$quarter-SNAPSHOT"
}
require(versionPattern.matches(version.toString())) {
    "Version '$version' is not year.quarter (26.4), year.quarter.hotfix (26.4.1), a Jenkins build (26.4.B25) " +
        "or a snapshot (26.4-SNAPSHOT)"
}

val pluginName = "Hawkeye"
val basePackage = "gg.moonrise.hawkeye"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // Moonrise plugin-engine: shaded and relocated (see shadowJar). Its runtime libraries (Spring, Moss,
    // Cloud, ConfigLib) are resolved by the engine's PaperPluginLoader at server start, so compileOnly here.
    implementation(libs.engine)
    compileOnly(libs.bundles.engineRuntime)
    compileOnly(libs.paper)
    // Hawkeye's own runtime libraries: resolved by HawkeyePluginLoader at server start, never shaded.
    compileOnly(libs.bundles.hawkeyeRuntime)

    // java-development skill: Lombok and @NotNull/@Nullable in main and test code.
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    compileOnly(libs.jetbrainsAnnotations)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
    testCompileOnly(libs.jetbrainsAnnotations)

    testImplementation(libs.paper)
    testImplementation(libs.bundles.engineRuntime)
    testImplementation(libs.bundles.hawkeyeRuntime)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.archunit)
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:all,-processing")
    }
    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }
    jar {
        enabled = false
    }
    build {
        dependsOn(shadowJar)
    }
    processResources {
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
    test {
        useJUnitPlatform()
        // The opt-in storage benchmarks hold whole segments of events in memory: HAWKEYE_TEST_HEAP=3g.
        providers.environmentVariable("HAWKEYE_TEST_HEAP").orNull?.let { heap -> maxHeapSize = heap }
        // MockBukkit builds a Bukkit LibraryLoader on every registerEvents call, and its Maven resolver asks
        // InetAddress.getLocalHost(). On macOS a host name missing from /etc/hosts makes each lookup wait 5 s
        // for mDNS, so the test JVM resolves names from this file only (docs/knowledge "Environment facts").
        val hosts = layout.projectDirectory.file("src/test/hosts")
        inputs.file(hosts).withPropertyName("hosts").withPathSensitivity(PathSensitivity.NONE)
        systemProperty("jdk.net.hosts.file", hosts.asFile.absolutePath)
        // HawkeyePluginLoaderTest compares the loader's coordinates with the catalog.
        val runtimeLibraries = listOf(libs.hikari, libs.sqlite, libs.mariadb, libs.postgresql, libs.caffeine,
            libs.lettuce, libs.zstd).joinToString(",") { library -> library.get().toString() }
        inputs.property("hawkeyeRuntimeLibraries", runtimeLibraries)
        systemProperty("hawkeye.runtime.libraries", runtimeLibraries)
    }
    // The shipped jar: build/libs/<pluginName>-<version>.jar (Hawkeye-26.4.jar, Hawkeye-26.4.B12.jar) with our
    // classes and the relocated engine only.
    shadowJar {
        archiveBaseName.set(pluginName)
        archiveClassifier.set("")

        // Paper provides itself and the loader resolves the engine's libraries: only the engine is bundled.
        dependencies {
            include(dependency("gg.moonrise.engine:plugin-engine-paper:.*"))
        }

        // The engine moves under our package so Moss's scan (main class package only) finds its beans; its
        // async command registry is replaced by command.registry.HawkeyeCommandRegistry.
        exclude("gg/moonrise/engine/paper/command/PaperCommandRegistry.class")
        relocate("gg.moonrise.engine", "$basePackage.libs.engine")
    }
}

val verifyPluginJar = tasks.register("verifyPluginJar") {
    description = "Checks the plugin jar: entry points, relocated engine, expanded descriptor, one command registry."
    group = "verification"
    val artifact = tasks.shadowJar.flatMap { it.archiveFile }
    val expectedVersion = project.version.toString()
    val ours = basePackage.replace('.', '/') + "/"
    val mainClass = "$basePackage.${pluginName}Plugin"
    val loaderClass = "$basePackage.loader.${pluginName}PluginLoader"
    val registryClass = "${ours}command/registry/${pluginName}CommandRegistry.class"
    inputs.file(artifact)
    val marker = layout.buildDirectory.file("verifyPluginJar/ok")
    outputs.file(marker)

    doLast {
        val file = artifact.get().asFile
        ZipFile(file).use { jar ->
            val names = jar.entries().asSequence().map { it.name }.toList()
            listOf(
                mainClass.replace('.', '/') + ".class",
                loaderClass.replace('.', '/') + ".class",
                "${ours}libs/engine/paper/PaperPlugin.class",
                "${ours}libs/engine/paper/loader/PaperPluginLoader.class",
                "paper-plugin.yml",
                // Spring lists a package's classes through its directory entry.
                ours,
            ).forEach { require(it in names) { "${file.name} is missing $it" } }

            val classes = names.filter { it.endsWith(".class") }
            val foreign = classes.filter { !it.startsWith(ours) }
            require(foreign.isEmpty()) {
                "only our classes and the relocated engine may be bundled, found ${foreign.take(5)}"
            }
            require(names.none { it.startsWith("gg/moonrise/engine/") }) { "the engine must be relocated" }
            val registries = classes.filter { it.endsWith("CommandRegistry.class") }
            require(registries == listOf(registryClass)) {
                "expected exactly our command registry $registryClass, found $registries"
            }

            val yml = jar.getInputStream(jar.getEntry("paper-plugin.yml")).use { it.readBytes().decodeToString() }
            require("version: '$expectedVersion'" in yml) { "paper-plugin.yml version not expanded" }
            require("main: $mainClass" in yml) { "paper-plugin.yml main is not $mainClass" }
            require("loader: $loaderClass" in yml) { "paper-plugin.yml loader is not $loaderClass" }
        }
        marker.get().asFile.writeText("ok\n")
    }
}

tasks.check {
    dependsOn(verifyPluginJar)
}

// Publishes the plugin jar to the Moonrise Nexus on request (`./gradlew publish -Pversion=26.4` with
// nexusUsername/nexusPassword); no CI does it. Snapshot versions go to maven-snapshots.
publishing {
    publications {
        create<MavenPublication>("plugin") {
            from(components["shadow"])
        }
    }
    repositories {
        maven {
            name = "nexus"
            val repository = if (version.toString().endsWith("-SNAPSHOT")) "maven-snapshots" else "maven-releases"
            url = uri("https://repo.moonrise.gg/repository/$repository/")
            credentials {
                username = findProperty("nexusUsername") as String?
                password = findProperty("nexusPassword") as String?
            }
        }
    }
}
