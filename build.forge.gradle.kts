plugins {
    id("java-library")
    id("me.modmuss50.mod-publish-plugin") version "0.8.4"
    // Forge 1.17-1.20.1. Same artifact and version as net.neoforged.moddev, which is why one
    // plugin declaration in stonecutter.gradle.kts covers both loaders. ForgeGradle 6 cannot be
    // used here: Stonecutter 0.8+ requires Gradle 9 and FG6 does not run on it.
    id("net.neoforged.moddev.legacyforge")
}

val mcVersion    = property("deps.minecraft") as String
val forgeVersion = property("deps.forge") as String
val modId        = property("mod.id") as String
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
            // Separate game directory, same reasoning as the NeoForge node: a client and a
            // dedicated server sharing run/ cannot both be up, and the collision surfaces as a
            // DirectoryLock IOException that looks like a mod fault. Keep the two loaders'
            // layouts identical so a test procedure written for one works on the other.
            gameDirectory = file("run-server")
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
    // pack_format differs per Minecraft version. On 1.20.1 the resource and data formats are
    // both 15, so the single number here is exactly right; 1.21.1 is where they split (34
    // resource / 48 data) and that node's build script explains why one value still serves.
    // The comment used to say "46 on 1.21.x", which is neither of them.
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
//
// ---------------------------------------------------------------------------------------------
// Guard: never ship a Forge jar that is not reobfuscated.
//
// Two same-named jars come out of this build (see the comment on `file` below). Telling them
// apart by eye is impossible, and 0.10.4 proved nobody would. So this task opens the jar that is
// about to be published and checks that a couple of classes reference Minecraft methods by their
// SRG names. The dev jar references them by their official names and fails here, instead of on
// every player's machine.
//
// The two probes are methods this mod calls unconditionally, so they exist in every build:
//   ModTriggers     -> CriteriaTriggers.register, which is m_10595_ once remapped
//   VoiceController -> Entity.getX,               which is m_20185_ once remapped
// Wired into `check`, so every build runs it, and into every publish task, so no route to a
// store skips it.
val verifyForgeReobf by tasks.registering {
    group = "verification"
    description = "Fails if the Forge jar still carries dev (official) Minecraft method names instead of SRG."
    val jar = tasks.named<Jar>("reobfJar").flatMap { it.archiveFile }
    // zipTree, not java.util.zip: inside a build script `java` is the JavaPluginExtension accessor
    // (see `java { withSourcesJar() }` above), so `java.util.zip.ZipFile` does not even resolve.
    val classes = project.zipTree(jar)
    inputs.file(jar)
    doLast {
        val archive = jar.get().asFile
        val probes = mapOf(
            "com/niko/voicespells/advancements/ModTriggers.class" to "m_10595_",
            "com/niko/voicespells/client/VoiceController.class"   to "m_20185_",
        )
        probes.forEach { (entry, srg) ->
            val cls = classes.matching { include(entry) }.files.singleOrNull()
                ?: throw GradleException("verifyForgeReobf: $entry is missing from ${archive.name}")
            val text = String(cls.readBytes(), Charsets.ISO_8859_1)
            if (!text.contains(srg)) {
                throw GradleException(
                    "verifyForgeReobf: ${archive.name} is NOT reobfuscated - $entry does not reference " +
                    "$srg. This is the dev jar (build/devlibs); publishing it crashes every Forge " +
                    "user at mod construction, as 0.10.4 did. The publishable jar is reobfJar's " +
                    "output in build/libs.")
            }
        }
        logger.lifecycle("verifyForgeReobf: ${archive.name} carries SRG names (${probes.size} classes checked)")
    }
}
tasks.named("check") { dependsOn(verifyForgeReobf) }
tasks.matching { it.name == "publishMods" || it.name == "publishModrinth" || it.name == "publishCurseforge" }
    .configureEach { dependsOn(verifyForgeReobf) }

publishMods {
    // reobfJar, NOT jar. ModDevGradle's legacyforge plugin redirects the plain jar task's output
    // into build/devlibs/ (official Mojang names, loadable only in a dev environment) and hands
    // build/libs/ to reobfJar, which remaps to the SRG names a real Forge 1.20.1 runtime uses.
    // Both archives carry the same file name, so nothing looks wrong. 0.10.4 was the first
    // release to go through this block and it uploaded the devlibs jar: every Forge user got
    //   NoSuchMethodError: CriteriaTriggers.register(CriterionTrigger)
    // at mod construction, because the runtime only knows that method as m_10595_. It had worked
    // in runClient, which is deobfuscated, and 0.10.3 had been uploaded by hand from build/libs.
    // Verified against the bytes Modrinth served: 23 official-named Minecraft calls, 0 SRG.
    file = tasks.named<Jar>("reobfJar").flatMap { it.archiveFile }
    displayName = "Incantation ${property("mod.version")} for Forge 1.20.1"
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
            minecraftVersions.add("1.20.1")
            modLoaders.add("forge")
            clientRequired = true
            serverRequired = true
            // Optional, not required: both mods.toml files declare irons_spellbooks as an
            // optional dependency and the mod loads and behaves sanely without it, so a store
            // page that says "required" contradicts the jar and makes launchers pull in a mod
            // the user may not want. Addon spell mods register into the same registry.
            optional("irons-spells-n-spellbooks")
            optional("curios")
        }
    }
    if (!mrId.isNullOrBlank() && !mrToken.isNullOrBlank()) {
        modrinth {
            projectId = mrId
            accessToken = mrToken
            minecraftVersions.add("1.20.1")
            modLoaders.add("forge")
            // Optional, not required: both mods.toml files declare irons_spellbooks as an
            // optional dependency and the mod loads and behaves sanely without it, so a store
            // page that says "required" contradicts the jar and makes launchers pull in a mod
            // the user may not want. Addon spell mods register into the same registry.
            optional("irons-spells-n-spellbooks")
            optional("curios")
        }
    }
}
