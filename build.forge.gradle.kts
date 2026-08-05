plugins {
    id("java-library")
    // Forge 1.17-1.20.1. Same artifact and version as net.neoforged.moddev, which is why one
    // plugin declaration in stonecutter.gradle.kts covers both loaders. ForgeGradle 6 cannot be
    // used here: Stonecutter 0.8+ requires Gradle 9 and FG6 does not run on it.
    id("net.neoforged.moddev.legacyforge")
}

val mcVersion    = property("deps.minecraft") as String
val forgeVersion = property("deps.forge") as String
val modId        = property("mod.id") as String
val voicechatApi = property("deps.voicechat_api") as String
val voskVersion  = property("deps.vosk") as String

version = "${property("mod.version")}+$mcVersion-forge"
group   = property("mod.group") as String

base {
    archivesName = "incantation"
}

// 1.20.1 runs on Java 17. Forge 47 refuses class files above major 61.
java.toolchain.languageVersion = JavaLanguageVersion.of(17)

// Loader-shaped classes, shared by every node on this loader. The @Mod entrypoint, networking,
// the config spec and the advancement triggers have genuinely different STRUCTURE per loader, so
// they are separate files rather than per-line conditionals. They are keyed on the loader and not
// on the node because 1.21 and 1.21.1 need byte-identical copies — per-node would mean maintaining
// the same file twice, which is the duplication this migration exists to remove.
//
// This is a different directory from versions/<node>/src, which Stonecutter registers itself; only
// re-registering THAT one causes duplicate-entry failures.
sourceSets["main"].java.srcDir(rootProject.file("loader/forge/java"))


// Loader-specific Java. Some classes are shaped by the loader rather than merely referencing it —
// the @Mod entrypoint, networking, the config spec, and the advancement triggers have genuinely
// different structure per loader, and expressing that with per-line conditionals would mean
// wrapping nearly every declaration. Those live here; everything portable stays in the shared
// tree at the repo root, which is the great majority of the codebase.
// NOTE: versions/<node>/src/main/java is registered by Stonecutter's own convention - adding it
// explicitly here registers it TWICE and fails sourcesJar with a duplicate-entry error. Loader-
// shaped classes (the @Mod entrypoint, networking, config spec, advancement triggers) simply go
// in that directory and are picked up; everything portable stays in the shared tree at the root.


// Forge-only resources. The two loaders disagree on file NAMES and DIRECTORY layout, not just
// content — mods.toml vs neoforge.mods.toml, data/<ns>/advancement/ vs advancements/, and a
// different pack_format — and none of that can be expressed with source conditionals. So the
// shared tree carries the NeoForge layout and this overlay supplies the Forge one.
// (versions/<node>/src/main/resources is registered by Stonecutter convention - adding it
//  explicitly registers it twice and fails with duplicate-entry errors.)

legacyForge {
    version = "$mcVersion-$forgeVersion"

    runs {
        register("client") {
            client()
            if (project.hasProperty("quickPlay")) {
                programArguments.addAll("--quickPlaySingleplayer", project.property("quickPlay").toString())
            }
        }
        // Mandatory — the dedicated-server crash regressed twice for want of this.
        register("server") {
            server()
            programArguments.add("--nogui")
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets["main"])
        }
    }
}

// Vosk depends on JNA 5.7.0 and Gradle resolves that as a hard requirement, beating Minecraft's
// "prefer 5.12.1" — so the runtime classpath ends up with jna 5.7.0 alongside jna-platform 5.12.1.
// That mismatched pair breaks oshi, which calls Memory.close() (added in JNA 5.12) and dies with
// NoSuchMethodError before the game finishes starting. Forcing the pair back together fixes it;
// 5.12.1 satisfies Vosk, which only needs >= 5.7.
//
// Distinct from the jarJar exclusion below: that stops JNA being NESTED in the shipped jar, where
// Minecraft already provides it. This governs the dev runtime classpath instead. Both are needed.
configurations.all {
    resolutionStrategy {
        force("net.java.dev.jna:jna:5.12.1", "net.java.dev.jna:jna-platform:5.12.1")
    }
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    maven("https://maven.maxhenkel.de/repository/public") { name = "Henkelmax (Simple Voice Chat)" }
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
}

dependencies {
    compileOnly("de.maxhenkel.voicechat:voicechat-api:$voicechatApi")

    // Dev-run mods for exercising the reflective Iron's Spells / Curios layer. These MUST go
    // through modRuntimeOnly rather than being dropped into run/mods: published 1.20.1 jars are
    // SRG-mapped, while this workspace uses Mojang official names, so an unremapped jar fails at
    // mixin apply with "@Shadow method m_21244_ was not located in the target class". The
    // remapping configuration translates them into the dev namespace.
    //
    // Sourced from local files rather than a maven so the run does not depend on network access
    // or on Modrinth's occasionally-broken POMs for these artifacts.
    // Dev-run mods for exercising the reflective Iron's Spells / Curios layer.
    //
    // These MUST use modRuntimeOnly and MUST be maven coordinates. Two separate constraints:
    // published 1.20.1 jars are SRG-mapped while this workspace uses Mojang names, so an
    // unremapped jar dies at mixin apply ("@Shadow method m_21244_ was not located in the target
    // class"); and the remapping is an artifact transform keyed on module artifacts, so a local
    // file dependency is rejected outright with "Cannot convert the provided notation to an
    // object of type DependencyConstraint: file collection".
    //
    // Runtime only - every call into these mods is reflective, so nothing here is needed to
    // compile. They exist purely so a dev run can exercise the integration layer.
    // Pinned by Modrinth VERSION ID, not version number. Version numbers are not unique across
    // loaders or Minecraft versions - asking for geckolib "4.8.3" returned a build whose reported
    // mod version did not satisfy Iron's Spells' "1.20.1:4.8.2 or above" requirement. IDs are
    // unambiguous.
    "modRuntimeOnly"("maven.modrinth:irons-spells-n-spellbooks:9v34JOKI")  // 1.20.1-3.16.2
    "modRuntimeOnly"("maven.modrinth:curios:IPQlZkz1")                     // 5.14.1+1.20.1
    "modRuntimeOnly"("maven.modrinth:geckolib:aC5KMoNg")                   // 4.8.4, 1.20.1 forge
    "modRuntimeOnly"("maven.modrinth:irons-lib:DbpRfa2k")                   // 1.20.1-2.1.0
    // Slug is "playeranimator", but the artifact is player-animation-lib-forge-*.jar - a
    // DIFFERENT mod from the similarly named PlayerAnimationLibNeoforge (modid
    // player_animation_library). Matching the wrong one leaves Iron's Spells unsatisfied.
    "modRuntimeOnly"("maven.modrinth:playeranimator:xe2EVE6q")              // 1.0.2-rc1+1.20-forge

    implementation("com.alphacephei:vosk:$voskVersion")
    "additionalRuntimeClasspath"("com.alphacephei:vosk:$voskVersion")
    "jarJar"("com.alphacephei:vosk") {
        this as ExternalModuleDependency
        version { strictly("[$voskVersion,1)"); prefer(voskVersion) }
        // Minecraft already ships JNA for oshi; nesting Vosk's older copy risks it winning.
        exclude(group = "net.java.dev.jna")
    }
}

val tokens = mapOf(
    "minecraft_version"       to mcVersion,
    "minecraft_version_range" to property("deps.minecraft_range") as String,
    "forge_version"           to forgeVersion,
    "forge_version_range"     to property("deps.forge_range") as String,
    "loader_version_range"    to property("deps.loader_range") as String,
    "mod_id"                  to modId,
    "mod_name"                to property("mod.name") as String,
    "mod_license"             to property("mod.license") as String,
    "mod_version"             to property("mod.version") as String,
    "mod_authors"             to property("mod.authors") as String,
    "mod_description"         to property("mod.description") as String,
    // pack_format differs per Minecraft version (15 on 1.20.1, 46 on 1.21.x).
    // Templating it keeps ONE shared pack.mcmeta instead of a per-node copy,
    // which also removes a duplicate-entry clash in sourcesJar.
    "pack_format"             to property("deps.pack_format") as String,
)

tasks.named<ProcessResources>("processResources") {
    val replacements = tokens
    inputs.properties(replacements)
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) { expand(replacements) }
    // Never ship the other loader's metadata or datapack layout.
    exclude("META-INF/neoforge.mods.toml")
    exclude("data/*/advancement/**")   // the 1.21.x singular form
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

java { withSourcesJar() }

