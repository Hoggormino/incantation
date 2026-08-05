# Where 0.9.5 stands

Everything that could be done without dragging a file into a browser is done. What's left is
in `to-upload/` and needs you to drop it, because the upload tool caps at 10 MB and its
`paths` argument doesn't serialise in this session.

## Done and verified live

**0.9.5 published** — Modrinth (live) and CurseForge (uploaded, auto-publishes after scan).
Both tagged 1.21.1 / NeoForge / Java 21, cross-checked against the jar's own
`neoforge.mods.toml` rather than trusting CurseForge's "copied from incantation-0.9.3.jar".

**Modrinth dependencies fixed on all five versions.** 0.9.5, 0.9.3, 0.9.2 and 0.9.1 had a
*completely empty* dependency list — only 0.9.0 declared any. Anyone installing through the
Modrinth app got Incantation with no Iron's Spells and no Simple Voice Chat. All five now
declare Iron's Spells + SVC required, Curios optional.

**The false grammar claim is gone from every surface it shipped on:** both storefront
summaries, both descriptions, the jar's `mod_description`, and the in-game config tooltip.

**Storefront copy corrected against the source** after an audit found claims the code never
supported:

- **"Macros — record a cast sequence, replay with one keybind"** described a feature that does
  not exist. There are four keybinds and none of them records anything. Replaced with quick
  recast (`]`), which is real.
- **"No network calls"** was false. `autoDownloadModel` defaults to on and pulls a ~128 MB
  model from alphacephei.com on first launch. Now stated plainly, with the opt-out.
- **Setup told people to download and unzip a model by hand** — stale, since the mod does it
  itself now. The manual route is kept as the offline alternative.
- **"a spell only fires if it's in your Curios slot, main hand, or off hand"** overstated it.
  The server default is `castMode = CURIO_SPELLBOOK`, under which a spellbook *in hand* is
  refused. Hands only count under `ANY_SPELLBOOK`.
- **The HUD's "live audio meter" does not exist.** That chip was removed (SVC draws its own
  indicator); a stale javadoc and an unused `METER_W` constant were all that remained.
- Missing free theme (Dusk) added; the phrasebook / non-English feature was shipping
  undocumented and is now listed.

## Left for you — files are in `to-upload/`

### 1. Publish the 1.20.1 Forge build

`incantation-0.9.5+1.20.1-forge.jar` (25 MB) is built and its metadata is correct:
`minecraft [1.20.1]`, `forge [47,48)`, `irons_spellbooks [1.20.1-3.15.0,)`.

Upload as a **separate version** on both sites — Forge, 1.20.1, Java 17.

**It has never been cast on by a human.** It boots, loads with Iron's Spells present, and
indexes 111 spells, which is exactly the level of verification that let 0.9.0 and 0.9.3 ship
a dedicated-server crash. Worth casting one spell before you announce it.

### 2. Replace two CurseForge gallery images

`02-features.png` and `03-owned.png` in `to-upload/` are corrected versions. The ones live on
CurseForge still say *"Grammar restricted to your owned spells"* and *"it isn't in the
grammar"* — the same claim removed from the text, still sitting there as pictures.

CurseForge → Media → Add media, drop both, then delete the two old ones (their thumbnails
still show the old wording).

Sources are in `gallery/src/` now, so this can't silently rot again:

```bash
chrome --headless --disable-gpu --window-size=1920,1080 --screenshot=../02-features.png 02-features.html
```

### 3. Modrinth gallery is still empty

Zero images there versus three on CurseForge. All eight in `gallery/` are accurate — I checked
each one; only the two above had problems.

## One thing I could not fix

The published **1.21.1** jar still carries the old `mod_description` internally, because it was
built before `gradle.properties` was corrected. It only shows in Minecraft's mod list, and
fixing it means re-uploading the jar to a version that already has downloads. The 1.20.1 jar
was built after the fix and is clean. Simplest is to let the next release carry it.

Delete this file when you're through.
