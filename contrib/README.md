# Contributed phrasebooks

Community translations of the spell phrases, kept here so they cannot be lost.

**Nothing in this directory ships yet.** These are not on the classpath and the mod does not read
them. `Phrasebook` loads exactly one file, `config/voicespells/phrasebook.json`, and there is no
bundled-pack mechanism — see [LANGUAGE_PLAN.md](../LANGUAGE_PLAN.md) for the design that would give
them one. Until that lands, the only way a player gets one of these is to copy it into their own
config directory by hand.

| File | Language | Coverage | Contributed by |
|---|---|---|---|
| `phrasebook-es.json` | Spanish | 293 of 294 entries | NeoTargetStudios (Spidercat0926) |

## About `phrasebook-es.json`

Spell phrases across nine mods, not just Iron's Spells:

| Namespace | Entries |
|---|---|
| `irons_spellbooks` | 105 |
| `ess_requiem` | 52 |
| `hazennstuff` | 36 |
| `cataclysm_spellbooks` | 34 |
| `discerning_the_eldritch` | 22 |
| `tunes_n_tomes` | 16 |
| `gtbcs_geomancy_plus` | 12 |
| `aero_additions` | 10 |
| `elemental_synergies` | 6 |

The addon coverage is the valuable part and the part that cannot be automated. Iron's Spells ships
its own `es_es` lang file, so a future auto-derivation could produce its 105 from the game's own
translations — but the other 188 come from mods that ship no translations in any language, and a
human wrote every one of them.

## Using one by hand

Copy it to `config/voicespells/phrasebook.json` in your instance, then install a Spanish Vosk model
— the words have to exist in the loaded model's lexicon or Vosk drops those phrases from the
grammar silently. `/voicespells vocab` reports what the current model can and cannot say.

## Adding another

Same format: the file the mod writes into `config/voicespells/` on first run, with the `override`
field filled in per spell. Keep the `default` column as it was generated; it is what lets entries
survive a spell being temporarily uninstalled.
