# 0.10.5

Everything since 0.10.4.

Two fixes people reported, and then the big one: the mod speaks Russian now, and every other
language becomes a file somebody can hand back rather than a fork of the source.

---

## Fixed

**0.10.4 crashed on startup on Forge 1.20.1.** If you are on Forge, 0.10.4 crashed the moment the
game tried to load the mod, and going back to 0.10.3 made it stop — so it looked like something
0.10.4 changed. Nothing in the code did. The Forge build produces two jars with the same name, one
that only runs inside a development environment and one that runs everywhere, and the release
tooling uploaded the wrong one. 0.10.3 had been uploaded by hand, which is why it was fine. The
tooling now picks the right jar, and the jar is checked before it goes out. Thanks to the person
who reported it with full logs — the "i9-13900KF" theory in that report is not it; your CPU is
fine. NeoForge was never affected.

**Imbued armor never cast.** Iron's Spells lets you imbue a chestplate with a spell, and saying that
spell did nothing — and if a worn chestplate was your only spell source, the microphone did not even
open under the default "Hold item" setting. The mod checked your hands and your Curios slot for
spell-carrying items and never looked at what you were wearing. It does now, for all four armor
slots, and casts from armor the same way Iron's Spells itself does. One deliberate detail: wearing an
ordinary mage chestplate with nothing imbued in it does not count as holding a spell focus. Every
Iron's Spells chestplate carries an empty spell slot internally, and treating that as "holding a
spellbook" would have left the microphone open for as long as the armor was on. Reported on
CurseForge — thank you.

---

## The interface can be translated

Until this release the mod had 47 translatable strings and around 350 English sentences compiled
into the Java. A translator had no file to work from — the only way to change a button label was to
edit the source and rebuild.

Every user-facing string is now a lang key: all of the config screens, the HUD, the guide, the
welcome wizard, the credits, the spell list, the test arena, the live monitor, and both `/voicespells`
command trees. That is **450 keys**, and every one of them is referenced by the code — no reference
without a string, no string without a reference.

Lines that carry a number take a placeholder instead of being glued together in Java, which is what
made the command output translatable at all.

## Russian

`ru_ru.json` ships in the jar. Set your game language to Русский and the whole interface follows.

It is machine-produced and has not yet been reviewed by a native speaker, so if something reads
oddly, it probably is odd — say so and it gets fixed.

Seven values stay in English on purpose: brand names like Iron's Spells and Curios API, and literal
config keys such as `castMode=`, which are not words to translate.

## The diagnostics screen too

Worth calling out separately, because it is the screen you open exactly when something has gone
wrong. Leaving it in English would have defeated the point of translating the rest. All 46 checks —
names and one-line reasons — now come from the lang file.

Two things stay as machine values on purpose: the microphone state and the model state. The code
compares them against fixed strings to decide whether the mic is healthy, so they are translated at
the point they are shown to you rather than at the point they are set.

## The Russian phrasebook is now blank on purpose

`contrib/phrasebook-ru.json` used to arrive with 106 entries copied from Iron's Spells' own Russian
translation. They have been removed, and the file is 294 blank overrides.

Those are two different things that look like one. A lang file translates the spell name **written
on your screen**. A phrasebook holds what you **say out loud**. A written name can be a phrase
nobody would speak that way, or can contain a word the speech model has no entry for — and when
that happens the phrase is dropped from the grammar and the spell simply never fires, with nothing
said anywhere.

Half a file of plausible entries that had never been spoken into a microphone was worse than an
empty one: a blank entry asks to be filled, a wrong one gets trusted. `/voicespells vocab` tells
you which of your phrases the loaded model can actually pronounce, and it takes seconds.

## For anyone who wants to translate it

Copy `assets/voicespells/lang/en_us.json` out of the jar, rename it to your locale, translate the
values and leave the keys alone. [`contrib/README.md`](contrib/README.md) has the three things that
will bite you — what `%s` and `§` mean, and why the guide screens need each paragraph re-wrapped
rather than translated line by line.

Thanks again to **NeoTargetStudios (Spidercat0926)** for the Spanish phrasebook and for a hand with
the mod itself.
