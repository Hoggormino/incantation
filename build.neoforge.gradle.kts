plugins {
    id("java-library")
    id("net.neoforged.moddev")
}

val mcVersion       = property("deps.minecraft") as String
val neoVersion      = property("deps.neoforge") as String
val modId           = property("mod.id") as String
val voicechatApi    = property("deps.voicechat_api") as String
val voskVersion     = property("deps.vosk") as String

version = "${property("mod.version")}+$mcVersion-neoforge"
group   = property("mod.group") as String

base {
    // Display name for the published jar. Decoupled from mod.id (which stays "voicespells" so
    // existing configs, advancements and packets still resolve).
    archivesName = "incantation"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

// Loader-specific Java. Some classes are shaped by the loader rather than merely referencing it —
// the @Mod entrypoint, networking, the config spec, and the advancement triggers have genuinely
// different structure per loader, and expressing that with per-line conditionals would mean
// wrapping nearly every declaration. Those live here; everything portable stays in the shared
// tree at the repo root, which is the great majority of the codebase.
// NOTE: versions/<node>/src/main/java is registered by Stonecutter's own convention - adding it
// explicitly here registers it TWICE and fails sourcesJar with a duplicate-entry error. Loader-
// shaped classes (the @Mod entrypoint, networking, config spec, advancement triggers) simply go
// in that directory and are picked up; everything portable stays in the shared tree at the root.


neoForge {
    version = neoVersion

    runs {
        register("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
            // -PquickPlay=<world> boots straight into a world. Microphone capture only runs in a
            // world, so this is what makes the audio path testable without clicking through menus.
            if (project.hasProperty("quickPlay")) {
                programArguments.addAll("--quickPlaySingleplayer", project.property("quickPlay").toString())
            }
        }
        // Mandatory. This mod ships client-only UI but must load headless, and the "client GUI
        // class on a dedicated server" crash regressed twice (0.9.0, then again in 0.9.3) purely
        // because there was no way to test for it locally. Run before every release.
        register("server") {
            server()
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
        }
        register("data") {
            data()
            programArguments.addAll(
                "--mod", modId, "--all",
                "--output", file("src/generated/resources/").absolutePath,
                "--existing", file("../../src/main/resources/").absolutePath
            )
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
    // Simple Voice Chat public API. compileOnly — SVC ships the API at runtime.
    compileOnly("de.maxhenkel.voicechat:voicechat-api:$voicechatApi")

    // Vosk offline speech recognition (JNA-backed; natives extract at runtime).
    implementation("com.alphacephei:vosk:$voskVersion")
    // additionalRuntimeClasspath and jarJar are configurations created by ModDevGradle, so they
    // have no generated Kotlin accessor — they are invoked by name.
    "additionalRuntimeClasspath"("com.alphacephei:vosk:$voskVersion")
    "jarJar"("com.alphacephei:vosk") {
        this as ExternalModuleDependency
        version { strictly("[$voskVersion,1)"); prefer(voskVersion) }
        // Vosk pulls JNA transitively and jarJar will nest it. Do not let it: Minecraft already
        // ships net.java.dev.jna 5.12.1 for oshi, and nesting an older copy risks it winning
        // resolution and breaking either Vosk's natives or oshi.
        exclude(group = "net.java.dev.jna")
    }
}

// Resolved here, at project scope. Inside a task-configuration block `property(...)` resolves
// against the TASK rather than the project, which fails with "unknown property" for every one of
// these — the values must be captured before the block.
val tokens = mapOf(
    "minecraft_version"       to mcVersion,
    "minecraft_version_range" to property("deps.minecraft_range") as String,
    "neo_version"             to neoVersion,
    "neo_version_range"       to property("deps.neoforge_range") as String,
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
    filesMatching(listOf("META-INF/neoforge.mods.toml", "pack.mcmeta")) { expand(replacements) }
    // The Forge target uses mods.toml at the same path; neither loader should ship the other's.
    exclude("META-INF/mods.toml")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

java { withSourcesJar() }
