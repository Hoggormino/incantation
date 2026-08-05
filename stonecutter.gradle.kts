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
