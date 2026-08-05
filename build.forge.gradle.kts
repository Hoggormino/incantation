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

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    maven("https://maven.maxhenkel.de/repository/public") { name = "Henkelmax (Simple Voice Chat)" }
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
}

dependencies {
    compileOnly("de.maxhenkel.voicechat:voicechat-api:$voicechatApi")

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
