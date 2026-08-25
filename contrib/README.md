# Contributed phrasebooks

Community translations of the spell phrases, kept here so they cannot be lost.

## Two different things, often confused

| | What it translates | Ships in the jar? |
|---|---|---|
| **`assets/voicespells/lang/*.json`** | The mod's own interface — buttons, tooltips, diagnostics, the guide | **Yes.** `en_us` and `ru_ru` as of 0.10.5 |
| **`contrib/phrasebook-*.json`** | The words you **say out loud** to cast a spell | **No** — see below |

Translating the interface does not change what you say, and translating the phrasebook does not
change the interface. A Russian player on an English speech model wants the first and not the
second; a Russian player on a Russian speech model needs both.

**Nothing in this directory ships yet.** These are not on the classpath and the mod does not read
them. `Phrasebook` loads exactly one file, `config/voicespells/phrasebook.json`, and there is no
bundled-pack mechanism — see [LANGUAGE_PLAN.md](../LANGUAGE_PLAN.md) for the design that would give
them one. Until that lands, the only way a player gets one of these is to copy it into their own
config directory by hand.

| File | Language | Filled | Contributed by |
|---|---|---|---|
| `phrasebook-es.json` | Spanish | 293 of 294 | NeoTargetStudios (Spidercat0926) |
| `phrasebook-ru.json` | Russian | 0 of 294 — **blank on purpose**, see below | — |

## Why a non-English model does nothing without one of these

Vosk runs a **restricted grammar** and silently discards any entry containing a word its model's
lexicon has no entry for. Spell phrases are derived from the English registry path, so loading a
Russian model against an English grammar drops every phrase and recognition never fires — no
error, nothing on screen. That is not a broken model; it is an English grammar against a Russian
dictionary. From 0.10.4 the mod at least says so instead of failing silently.

## `phrasebook-ru.json` is deliberately empty

It used to ship 106 entries seeded from Iron's Spells' own `ru_ru.json`. Those are gone, and the
file is now 294 blank overrides.

The reason is the distinction in the table above. Iron's Spells' `ru_ru.json` translates spell
names **as written on a screen**. A phrasebook needs what a player **says out loud**, and the two
are not the same string:

- A written name can be a phrase nobody would speak aloud in that form.
- A written name can contain words the Vosk lexicon does not hold, in which case the entry is
  dropped from the grammar and that spell simply cannot be cast — silently.
- Seeded entries look finished. A blank one asks to be filled; a wrong one gets trusted.

Half a file of plausible-looking entries that had never been spoken into a microphone was worse
than an empty one, because the failure is invisible: the phrase is dropped, nothing is logged on
screen, and the spell just never fires.

**If you are filling it in:**

1. Write what you would actually say, not what the game prints.
2. Run `/voicespells vocab` in-game. It lists exactly which of your phrases the loaded model cannot
   pronounce — that is the only reliable check, and it takes seconds.
3. Watch `ё` vs `е`. Russian Vosk lexicons commonly normalise `ё` to `е`, and a word the lexicon
   does not hold is dropped without a word. If a phrase with `ё` never triggers, try the `е`
   spelling.
4. Only 106 of the 294 are Iron's Spells' own. The other 188 belong to addon mods, none of which
   ship a translation in any language, so nothing can be derived for them:

   | Mod | Spells | | Mod | Spells |
   |---|---:|---|---|---:|
   | `irons_spellbooks` | 106 | | `discerning_the_eldritch` | 22 |
   | `ess_requiem` | 52 | | `tunes_n_tomes` | 16 |
   | `hazennstuff` | 36 | | `gtbcs_geomancy_plus` | 12 |
   | `cataclysm_spellbooks` | 34 | | `aero_additions` | 10 |
   | | | | `elemental_synergies` | 6 |

## Using one by hand

Copy it to `config/voicespells/phrasebook.json` in your instance, then install a matching Vosk
model from [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models).

## Adding another language

```
python tools/seed_phrasebook.py <irons_spellbooks jar> <lang> <existing phrasebook> <out>
```

Seeds the Iron's Spells entries from the game's own lang file and leaves the addon spells blank.
Iron's Spells ships translations for `es`, `fr`, `ru`, `it`, `pl`, `pt_br`, `ja`, `ko`, `zh`, `uk`
and `vi` — but a language also needs a Vosk model to be usable, and it ships **no German lang file
at all**, which is why German needs a hand-written pack rather than a model.

Read the note above before trusting what it seeds: the seeder gives you written names, and a
phrasebook wants spoken ones. Treat its output as a list of spells to work through, not as a
translation that is already done.

## Translating the interface

That is a `lang` file, not a phrasebook. Copy
[`src/main/resources/assets/voicespells/lang/en_us.json`](../src/main/resources/assets/voicespells/lang/en_us.json)
to `<locale>.json` in the same directory and translate the values, leaving the keys alone.

Three things that will bite you:

- **`%s` is a placeholder.** Every `%s` in the English must appear the same number of times in the
  translation. Minecraft's formatter understands `%s` and `%1$s` only — `%d` and `%.2f` do not work.
- **`§` starts a colour code.** `§c`, `§f` and `§e` are formatting, not text. Keep them.
- **The guide, credits and welcome screens draw one key per rendered line** (`voicespells.help.p1.1`,
  `.p1.2`, …). They are not wrapped for you. Translate each paragraph as a whole and then re-wrap it
  across the same keys, keeping each line no longer than the English line it replaces — anything
  wider gets ellipsized.
