pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.parchmentmc.org")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.7"
}

rootProject.name = "voicespells"

stonecutter {
    create(rootProject) {
        // version(<node/project name>, <logical version>)
        //
        // The two-argument form is load-bearing. If the loader suffix were part of the LOGICAL
        // version, Stonecutter's semver parser would read "-forge" as a pre-release tag, making
        // "1.20.1-forge" < "1.20.1" — which silently inverts every `>=` predicate in the source
        // comments. The node name carries the loader; the logical version stays a bare semver.
        //
        // Each node gets its own buildscript, which is how one tree spans two loaders: Forge
        // 1.20.1 needs ModDevGradle's legacyforge plugin, NeoForge 1.21.x needs the regular one.
        version("1.21.1-neoforge", "1.21.1").buildscript = "build.neoforge.gradle.kts"

        version("1.20.1-forge",  "1.20.1").buildscript = "build.forge.gradle.kts"

        version("1.21-neoforge",   "1.21"  ).buildscript = "build.neoforge.gradle.kts"

        vcsVersion = "1.21.1-neoforge"
    }
}
