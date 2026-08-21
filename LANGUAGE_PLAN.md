# Language switching — the simpler model

Replaces the staged plan (Stage 3 "bundled phrase packs", Stage 4 "derive from Iron's Spells
lang files"). Same outcome, one mechanism instead of two, and no new file format.

Everything marked **verified** below was checked against the actual source, the actual Iron's
Spells jar, or the actual Vosk model on 2026-08-21. Everything else is a judgement call.


## The design in five lines

1. **One config key, `language`.** It replaces both `modelPath` and `modelId`.
2. **A language pack *is* a `phrasebook.json`**, shipped inside the jar. Same schema, same parser,
   same file a player already edits. NeoTargetStudios' Spanish file drops in unconverted.
3. **`auto` follows the Minecraft language, but only into a language we ship a pack for.** No
   coverage threshold, no guessing — the pack existing *is* the statement "a human verified this".
4. **Every spell can be said two ways**: the name printed in the spellbook (localized, free at
   runtime) and today's English id-derived name. Both go in the grammar, so nothing that works
   today stops working.
5. **A phrase containing a word the loaded model doesn't know is dropped whole and named**, not
   silently degraded to a fragment.

There is no staging. Nothing ships needing a later stage to make sense.


## Why this is simpler than the staged plan

| Staged plan | Here |
|---|---|
| A new bundled pack format | A pack is a `phrasebook.json` |
| A second parser for it | One parser, one set of malformed-input behaviours |
| Stage 4: parse Iron's Spells' lang files | Deleted — `SpellInfo.displayName()` already resolves them |
| Bundled translation data to keep current | None. Pack entries are keyed by spell id, so a stale entry is inert, not wrong |
| `modelPath` + `modelId` | One `language` key (the merge `CONFIG_AUDIT.md` already asked for) |

**Verified:** `SpellInfo.nameKey` is `spell.<namespace>.<path>`, byte-identical to Iron's Spells'
own lang keys (`spell.irons_spellbooks.fireball`). `displayName()` already resolves it with an
English fallback. Localized names cost nothing to obtain — `SpellIndex` simply never used them,
deriving from the resource path instead via `phraseFromPath(id.getPath())`.

**Verified:** `SpellIndex` is client-only. `SpellCaster` never references it; the cast payload
carries a spell id. So the player's language is available exactly where phrases are built.


## Four live bugs found on the way here

These are independent of the design and each ships on its own.

1. **`modelId` is inert on any install with a legacy model directory.**
   `ModelCatalog.resolveModelDir` returns `legacyDir()` whenever it looks like a model, *before*
   consulting `modelId`. Everyone who used the mod before the catalogue existed has
   `config/voicespells/model/` populated, so changing `modelId` does nothing for them, silently.

2. **`ModelDownloader` is a second source of truth.**
   `ensureModel(Path modelDir, …)` takes the directory as a parameter, then ignores it and reads
   `VoiceSpellsConfig.cModelId` to decide *what to download*. A player with `modelPath` pointed at
   an empty directory gets the **English** archive downloaded into it.

3. **The hardcoded English control words are silently dropped on any non-English model.**
   **Verified by parsing `es_grhead.bin`** (the Spanish model's own symbol table, 100,006 words):
   `spell` is **absent**; `yes` and `no` survive only as loanwords. So `spell one`…`spell nine`
   (voice hotbar select) vanish from the grammar with nothing said anywhere. Reachable today —
   `README.md` lines 136-138 tell non-English players to point `modelPath` at a foreign model.

4. **No language tag is ever normalized.** Minecraft hands out `es_mx`, `pt_br`, `en_gb`;
   catalogue entries are tagged `es`, `fr`. Nothing anywhere maps between them.


## The catalogue is missing eight models

`ModelCatalog` lists six (en×2, es, ru, fr, de). Vosk publishes small models for **pl, pt, it, uk,
ja, ko, cn, vn** as well. Cross-referenced against Iron's Spells 3.16.2's real lang files
(229 spell-name keys, measured 2026-08-21):

| Language | Translated | Vosk small model | In catalogue |
|---|---|---|---|
| ru | 219 (95%) | yes | yes |
| ja / ko | 219 (95%) | yes | **no** |
| pt_br | 218 (95%) | yes | **no** |
| zh_cn | 218 (95%) | yes | **no** |
| pl | 216 (94%) | yes | **no** |
| fr | 214 (93%) | yes | yes |
| uk | 197 (86%) | yes | **no** |
| it | 182 (79%) | yes | **no** |
| **es** | **165 (72%)** | yes | yes |
| de | **none** | yes | yes |

Two things fall out of this:

- The old conclusion that only es/fr/ru can work was an artefact of the catalogue being
  incomplete, not a fact about the world. **Portuguese and Polish sit at ~95% and are one line of
  config away.** Spanish has the *worst* coverage of any translated language.
- German is unchanged and still translation-blocked, exactly as previously concluded. A model does
  not help it; a lang file would.

CJK (ja/ko/zh) needs separate thought — those languages don't word-segment on spaces, so "phrases"
may not behave the same way. Don't promise them without testing.


## What each player actually gets

- **Spanish, game in Español** — `auto` → `es`, pack found, ~39 MB download through the existing
  progress UI, 293/294 spells including the 188 addon ones. Zero config, zero JSON editing.
- **Spanish, addons the pack author never had** — those fall back to their English id phrase
  against a Spanish lexicon. Most get dropped, and are *named* rather than silently lost. Their
  pre-seeded `phrasebook.json` makes filling the gap a short edit instead of a 294-line project.
- **French / Russian** — nothing changes by default. They get one offer: "Iron's Spells names 214
  of 229 spells in French — try it? (~41 MB)". Opt-in, because nobody has yet said those words
  into Vosk-fr and an unrequested 39 MB download is a behaviour change to a published mod.
- **German** — unchanged, and the offer never appears, because the number reads zero. Parked by
  data rather than by a comment.
- **English (the majority)** — strictly additive. Same phrases, plus spells that now also answer
  to the name on screen, plus hotbar select stops being silently dropped on foreign models.


## What this does not solve

1. **Addon spells.** They ship no translations in any language. On a modpack, non-English coverage
   rests entirely on a human-written pack. This makes that work a file copy in a format that
   already exists, and makes the gap countable. It does not remove it.
2. **Lexicon membership is not recognisability.** A word being in the model's dictionary does not
   mean Vosk will hear it correctly. Don't put "246 of 294 castable" on a screen — the honest
   number is "N phrases loaded into the grammar".
3. **French and Russian are unproven.** The coverage numbers count lang-file keys, not successful
   casts. That is exactly why they are an offer and not a default.
4. **The mod's own UI is English-only.** Every message explaining the language situation is in
   English, addressed to someone who chose not-English. That is a contribution slot
   (`lang/es_es.json`), not a code task — and it pairs naturally with a phrase pack: one PR, two
   files, no code.


## Implementation order

Steps 1-3 are standalone bug fixes and can ship before any of the design lands.

0. **Find and commit NeoTargetStudios' Spanish file.** It is not in the repo. Nothing else matters
   if it is lost.
1. Language-tag normalization, and the `legacyDir` precedence fix.
2. Control words → lang keys, and guard them against the lexicon. Fixes bug 3 above.
3. `ModelDownloader` reads the resolved directory instead of `cModelId`. Fixes bug 2.
4. Merge `modelPath` + `modelId` → `language`, **with** the migration in the same commit: read the
   old TOML as plain text in the mod constructor, before the config spec registers and deletes
   unknown keys.
5. The drop-whole guard, as a filtered **view** — never by removing entries from `phraseToId`,
   which `allSpells()`, `lookupWithTier` and `vocabularyReport` all iterate. A subtractive gate
   would empty the very report meant to surface the problem. Fail open when the lexicon is
   unreadable.
6. `Phrasebook.parse` extraction + bundled-pack loading.
7. **Ship `es.json`.** Spanish works end to end. This is the release worth a changelog.
8. The localized-name candidate, **last** — it is the only piece with a timing dependency, and if
   it misbehaves everything above still stands.
9. Visibility: "Listening in: Español" on the status line, a Diagnostics row, a world-join notice.
10. Docs — `README.md` 136-138 still tells non-English players to set a key that will not exist.

Add the eight missing `ModelCatalog` entries whenever convenient; each is one line, and Portuguese
and Polish are the best-value languages in the table.
