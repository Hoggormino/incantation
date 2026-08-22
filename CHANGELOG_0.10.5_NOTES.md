# 0.10.5 — raw notes

**These are notes, not the changelog.** Facts and reasons, for Hoggormino to write the release in
his own words. 112 commits since 0.10.3. 0.10.4 was staged but never published, so this supersedes
it — everything below is new to anyone upgrading from 0.10.3.

Ordered by what a player would notice, not by when it was done.

---

## Things that were broken and are now fixed

**Combat-only blocked every cast, always.** The check read a field vanilla only ever writes on the
server, so the client read zero forever and the answer was permanently "not in combat" — even
while a zombie was hitting you. It now samples `hurtTime` each tick and also counts a living
hostile within 12 blocks, so opening a fight with a spell works.

**Blocked casts said nothing.** Every gate — combat, AFK, sneak — rejected into a buffer only the
debug screens read, so a blocked cast was indistinguishable from a dead microphone. The miss toast
now names the gate.

**The cast queue could not hold more than one spell.** An entry's 1.5s life was counted from when
it was spoken and checked *before* the still-casting guard, so starting a three-second channel and
naming the next spell — the obvious way to use a queue — aged the entry out before the first cast
finished. The clock now runs from when the cast ends. Queue depths 2–5 mean something for the
first time.

**"Pause when AFK" paused nothing.** The microphone stayed open and Vosk kept decoding; the setting
was one rejection at the end of the pipeline. It now closes the device. AFK also counted only
standing still, so mining, AFK-fishing or holding position in a mob farm read as away — turning,
swinging and using an item now count as activity.

**Hands-free "yes" did nothing** while its description promised it answered the mod's prompts. It
now accepts the "did you mean…?" alias suggestion, which is the only prompt the mod shows.

**The config screen crashed the game on Forge.** Opening it threw on the first frame that drew a
tab, so the screen was unopenable on 1.20.1 for the entire UI rework. Vanilla's own tab arguments
are only safe at vanilla's tab height.

**`modelId` did nothing for anyone with an older install.** The legacy model directory was checked
first and unconditionally, so anyone who had used the mod before the model catalogue existed was
silently kept on English no matter what they set.

**The model downloader fetched the wrong model.** It decided what to download by re-reading the
config instead of using the directory it was told to fill, so pointing `modelPath` at an empty
folder unpacked the *English* archive into it.

**Voice-cast advancement streaks could be forged** — the streak came from the client. The server
counts it now, as it already did for the total.

**The mod's own advancements could not be earned at all** — cast totals were not persisted.

---

## Non-English speech

A player reported the Russian model not triggering at all. It is not a broken model: Vosk runs a
restricted grammar and silently drops any phrase containing a word its lexicon lacks, and the
phrases are English spell names. Against a Russian model every phrase is dropped.

- The mod now **says so** instead of failing silently — it counts the unpronounceable phrases, logs
  them, and puts a line in chat when you join a world. `/voicespells vocab` lists the details.
- Hardcoded English control words are filtered against the loaded model's lexicon rather than
  silently discarded by Vosk.
- **`contrib/phrasebook-es.json`** — a Spanish phrasebook contributed by NeoTargetStudios
  (Spidercat0926): 293 of 294 entries, including 188 addon spells that ship no translations in any
  language.
- **`contrib/phrasebook-ru.json`** — a Russian starter, 106 entries auto-filled from Iron's Spells'
  own `ru_ru.json`, leaving the addon spells for a contributor.
- **`tools/seed_phrasebook.py`** does that for any language Iron's Spells translates.
- Neither file ships in the jar yet. See `LANGUAGE_PLAN.md`.

**Spell names now display in the player's own language** on every screen and on the HUD, using
Iron's Spells' own translations.

---

## Removed

**Voice hotbar select is gone.** It injected nine two-word phrases into the grammar, and Vosk
force-fits audio onto the nearest entry — so every one of them was another thing a spell name could
be misheard as, for a feature that only moved a selection. It also could not work on any
non-English model, and its own tooltip told players to say "slot one" while the code listened for
"spell one".

---

## Interface

The screens were rebuilt on vanilla widgets and vanilla sprites rather than hand-drawn chrome.
Worth calling out for players:

- **Every HUD chip now has a background.** The colour keys ship fully transparent, so the queue
  chip, miss toast, heard chip and suggestion prompt have been bare text lying on the world for
  everyone who never hand-edited their config.
- **The cooldown indicator moved out of the middle of the screen.** It was pinned under the
  crosshair; it is now a chip in the HUD stack, in whichever corner you chose, with a toggle and a
  vertical offset on the HUD tab.
- Tabs, scrollbars, list rows and buttons are Minecraft's own now, so a GUI resource pack reaches
  them.
- A **Credits** screen, with the libraries and their licences.
- Text that ran off panels in a dozen places is bounded; the device picker no longer eats the words
  "System default".

---

## Still known-imperfect, not fixed

Say these plainly or not at all — they will otherwise come back as bug reports.

- **French and Russian are unproven.** Iron's Spells translates 214/229 and 219/229 of its spell
  names respectively, but nobody has said those words into a Vosk model of that language yet.
- **German cannot work yet** — Iron's Spells ships no German lang file, so there are no German
  spell names to derive. It needs a hand-written pack, not a model.
- **Addon spells ship no translations in any language**, so on a modpack every non-English player
  depends on a hand-written phrasebook.
- **"Pause when AFK" is still position-and-input based**, not presence-based. Sitting perfectly
  still and silent for the timeout will still pause it.
- **Sneak-to-cast gives no feedback** beyond the miss toast, and holding sneak on a mount dismounts
  you (vanilla behaviour).
