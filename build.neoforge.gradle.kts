plugins {
    id("java-library")
    id("me.modmuss50.mod-publish-plugin") version "0.8.4"
    id("net.neoforged.moddev")
}

val mcVersion       = property("deps.minecraft") as String
val neoVersion      = property("deps.neoforge") as String
val modId           = property("mod.id") as String
val voskVersion     = property("deps.vosk") as String

version = "${property("mod.version")}+$mcVersion-neoforge"
group   = property("mod.group") as String

base {
    // Display name for the published jar. Decoupled from mod.id (which stays "voicespells" so
    // existing configs, advancements and packets still resolve).
    archivesName = "incantation"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

// Loader-shaped classes, shared by every node on this loader. The @Mod entrypoint, networking,
// the config spec and the advancement triggers have genuinely different STRUCTURE per loader, so
// they are separate files rather than per-line conditionals. They are keyed on the loader and not
// on the node because 1.21 and 1.21.1 need byte-identical copies — per-node would mean maintaining
// the same file twice, which is the duplication this migration exists to remove.
//
// This is a different directory from versions/<node>/src, which Stonecutter registers itself; only
// re-registering THAT one causes duplicate-entry failures.
sourceSets["main"].java.srcDir(rootProject.file("loader/neoforge/java"))


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
            // Its own game directory, separate from the client's run/. This mod is client-server
            // by nature -- the client hears the spell, the server casts it -- so the useful test
            // is a client connected to a dedicated server, both running at once. Sharing run/
            // makes that impossible: whichever starts second dies on a DirectoryLock over the
            // level, or cannot even rotate logs/latest.log, and the failure reads like a mod bug
            // rather than two processes fighting over one folder. It has already cost real time
            // twice.
            gameDirectory = file("run-server")
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
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
}

dependencies {
    // Simple Voice Chat public API. compileOnly — SVC ships the API at runtime.

    // Vosk offline speech recognition (JNA-backed; natives extract at runtime).
    implementation("com.alphacephei:vosk:$voskVersion")
    // additionalRuntimeClasspath and jarJar are configurations created by ModDevGradle, so they
    // have no generated Kotlin accessor — they are invoked by name.
    "additionalRuntimeClasspath"("com.alphacephei:vosk:$voskVersion")
    // NOTE on dev-run mods: do NOT use additionalRuntimeClasspath for them. It puts a mod's
    // CLASSES on the classpath without registering it with FML, so the game reports the mod as
    // missing while this mod's reflection still finds its classes and tries to use them - the
    // worst of both worlds. NeoForge needs no remapping, so dev mods simply go in the run
    // directory's mods/ folder (versions/<node>/run/mods).

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
    // pack_format differs per Minecraft version, and 1.21.1 splits it: the RESOURCE format is 34
    // and the DATA format is 48. The 34 shipped here is the resource one (the comment used to
    // claim "46 on 1.21.x", which is neither). One number is still correct for both halves,
    // because NeoForge hides a mod's own pack and parents it under a synthetic always-compatible
    // pack, so the data side is never gated on this value — the voicespells advancements load on
    // 1.21.1 exactly as they should. On 1.20.1 the two formats are both 15 and the question
    // does not arise.
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

// ---------------------------------------------------------------------------------------------
// Publishing.
//
// Exists so a release is one command rather than a hand-driven web form. The form is where this
// project has been bitten before: CurseForge inherits a file's game version and loader from the
// LAST file you uploaded rather than from the jar, so a 1.20.1 build can go out tagged 1.21.1 and
// nobody notices until it crashes. Declaring them here means they come from the build that
// produced the jar and cannot drift.
//
// Needs three things before it will run, none of which live in the repository:
//   * publish.curseforge.id / publish.modrinth.id in gradle.properties (project ids, not slugs)
//   * CURSEFORGE_TOKEN / MODRINTH_TOKEN in the environment
// Without them the tasks are skipped rather than failing, so an ordinary build is unaffected.
publishMods {
    file = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    displayName = "Incantation ${property("mod.version")} for NeoForge 1.21.1"
    version = property("mod.version") as String
    type = STABLE
    changelog = rootProject.file("CHANGELOG_${property("mod.version")}.md")
        .takeIf { it.exists() }?.readText() ?: "See the repository for changes."

    val cfId = providers.gradleProperty("publish.curseforge.id").orNull
    val mrId = providers.gradleProperty("publish.modrinth.id").orNull
    // Token from ~/.gradle/gradle.properties first, environment second. The home file is the
    // standard place for a credential that must never enter a repository, and unlike an
    // environment variable it is visible to any shell that runs the build rather than only to the
    // one it was exported in.
    val cfToken = providers.gradleProperty("curseforgeToken").orNull
        ?: providers.environmentVariable("CURSEFORGE_TOKEN").orNull
    val mrToken = providers.gradleProperty("modrinthToken").orNull
        ?: providers.environmentVariable("MODRINTH_TOKEN").orNull

    if (!cfId.isNullOrBlank() && !cfToken.isNullOrBlank()) {
        curseforge {
            projectId = cfId
            accessToken = cfToken
            minecraftVersions.add("1.21.1")
            modLoaders.add("neoforge")
            clientRequired = true
            serverRequired = true
            requires("irons-spells-n-spellbooks")
            optional("curios")
        }
    }
    if (!mrId.isNullOrBlank() && !mrToken.isNullOrBlank()) {
        modrinth {
            projectId = mrId
            accessToken = mrToken
            minecraftVersions.add("1.21.1")
            modLoaders.add("neoforge")
            requires("irons-spells-n-spellbooks")
            optional("curios")
        }
    }
}
