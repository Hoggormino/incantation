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

        // Minecraft 1.21 is deliberately NOT a target. It builds fine, but the mod would be
        // permanently inert there: Iron's Spells requires Curios as a MANDATORY dependency, and
        // Curios has no 1.21 build at all - it went straight from 1.20.x to 1.21.1. Iron's Spells
        // therefore cannot run on 1.21, so nothing would ever be castable.
        //
        // Note Modrinth tags several Iron's Spells builds as supporting 1.21; those tags are
        // wrong. 3.12.3 is tagged 1.21 but its own metadata declares [1.21.1,1.21.2), and the one
        // build that genuinely allows 1.21 (3.8.8) still hard-requires the Curios that does not
        // exist. Trust the jar's declared ranges, not the storefront tags.

        vcsVersion = "1.21.1-neoforge"
    }
}
