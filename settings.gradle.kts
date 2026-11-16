plugins {
    // Downloads the Java 25 toolchain when the machine has none (CI and hosts without a local JDK 25).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "hawkeye"

// Every repository is declared here; a project that declares its own fails the build.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()

        // PaperMC (paper-api and its dependencies)
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }

        // Moonrise (plugin-engine, Moss): anonymous read, asked only for gg.moonrise groups.
        listOf("maven-releases", "maven-snapshots").forEach { repository ->
            maven("https://repo.moonrise.gg/repository/$repository/") {
                name = "moonrise-$repository"
                content { includeGroupByRegex("gg\\.moonrise\\..*") }
            }
        }
    }
}
