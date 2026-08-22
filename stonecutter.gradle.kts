plugins {
    id("dev.kikugie.stonecutter")
    // Both loaders come from ONE artifact at one version — legacyforge covers Forge 1.17–1.20.1,
    // moddev covers NeoForge. Applied for real inside the per-node buildscripts.
    id("net.neoforged.moddev")             version "2.0.143" apply false
    id("net.neoforged.moddev.legacyforge") version "2.0.143" apply false
}

stonecutter active "1.21.1-neoforge" /* [SC] DO NOT EDIT */

stonecutter parameters {
    // Exposes the loader as a preprocessor constant, so shared source can branch on LOADER and
    // not only on version:
    //     //? if forge {
    //     /^ ... Forge-only code ^/
    //     //?} else {
    //     ... NeoForge code
    //     //?}
    //
    // The node name is "<version>-<loader>", so the loader is whatever follows the last dash.
    constants.match(current.project.substringAfterLast('-'), "forge", "neoforge")
}

// ---------------------------------------------------------------------------------------------
// Push the store description to Modrinth.
//
// Loader-agnostic, so it lives on the root rather than being run twice from two build scripts —
// the description belongs to the project, not to a jar. CurseForge has no equivalent: it exposes
// no endpoint for a project's description at all, so that side stays manual whatever token exists.
//
// Reads the token from ~/.gradle/gradle.properties and never prints it.
tasks.register("publishModrinthDescription") {
    group = "publishing"
    description = "Upload MODRINTH_DESCRIPTION.md as the Modrinth project body."
    doLast {
        val id = providers.gradleProperty("publish.modrinth.id").orNull
        val token = providers.gradleProperty("modrinthToken").orNull
            ?: providers.environmentVariable("MODRINTH_TOKEN").orNull
        val body = rootProject.file("MODRINTH_DESCRIPTION.md")
        if (id.isNullOrBlank())    throw GradleException("publish.modrinth.id is not set")
        if (token.isNullOrBlank()) throw GradleException("modrinthToken is not set")
        if (!body.exists())        throw GradleException("MODRINTH_DESCRIPTION.md is missing")

        val payload = groovy.json.JsonOutput.toJson(mapOf("body" to body.readText()))
        // java.net.http, not HttpURLConnection: the latter hardcodes its allowed method list and
        // rejects PATCH outright with "Invalid HTTP method", which is exactly the verb the
        // Modrinth project endpoint takes.
        val client = java.net.http.HttpClient.newHttpClient()
        val req = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI("https://api.modrinth.com/v2/project/$id"))
            .header("Authorization", token)
            .header("Content-Type", "application/json")
            .header("User-Agent", "Hoggormino/incantation/publish")
            .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
        val code = res.statusCode()
        // 204 is the documented success. Anything else prints the server's reason, never the token.
        if (code !in 200..299) {
            throw GradleException("Modrinth refused the description: HTTP $code ${res.body()}")
        }
        logger.lifecycle("Modrinth description updated (${body.length()} bytes, HTTP $code)")
    }
}
