# 0.10.6

Everything since 0.10.5.

Two bugs from the CurseForge comments and one found on the way to the second. The first two are
the same unpleasant kind: the mod heard you and then quietly did the wrong thing about it — once
by refusing a repeat, once by casting on a guess it had not finished checking.

---

## Fixed

**Some spells could only be cast once.** Say a spell, and saying it again did nothing — no cast,
and nothing in the Live Monitor either, as though the microphone had stopped working. Saying a
*different* spell first brought it back, and everything looked fine in the Test Arena. Three
symptoms, one cause.

The mod counts utterances so it can tell "the recogniser is still chewing on the word I just said"
apart from "the player said it again", and it only counted a new one when the recogniser produced a
running guess before its final answer. It does not always produce one. With a short spell name the
speech engine often keeps its running guess empty and commits everything at the end, so the mod saw
a single answer with nothing before it. Two of those in a row left the counter frozen, and from then
on every repeat of that spell was treated as an echo of the first — for as long as you kept saying
it. Saying a different spell reset the comparison, which is the workaround the reporter found on
their own.

An utterance is finished when the recogniser gives its final answer, so anything after that is the
next one. That is now what the mod counts, and a spell can be cast as many times in a row as you
can say it.

The silence was a second bug on top of the first. A suppressed repeat wrote nothing anywhere, so
there was no way to tell it apart from a dead microphone — which is exactly how it was reported.
The Live Monitor now shows `(repeat too soon)` for a repeat that lands inside the echo window, one
line per utterance. The Test Arena looked healthy throughout because it stops at "a menu is open"
before it ever reaches the echo check. Thanks to **tomasek1a** for a report that described all
three symptoms precisely enough to find the cause from.

**Invisibility could be cast from words that sound nothing like it.** The recogniser is only ever
allowed to hear spell names, so when it hears something else it picks the closest name it knows —
and a long name is the natural magnet, because it can absorb the most audio. The mod then cast on
the recogniser's *running guess*, before the final answer, and that path had no check on it at
all: the confidence setting only ever applied to the final result.

Two things changed. Every match now has to have been *spoken* for long enough to plausibly be
that phrase — 25 ms per letter by default (`minMsPerLetter`; "heal" 100 ms, "invisibility"
300 ms), measured from the recogniser's own word timings. Real spell names measure roughly
50-120 ms per letter; short one-syllable names like "shield" are the tightest, around 40, which is
why the default sits well under that rather than close to it. A running guess that comes up short
is not cast — the final answer decides, and nothing is held waiting for more audio. Only when the
completed word still comes up short does it get rejected, shown as `(too short 150ms < 300ms)` in
the Live Monitor. And `perSpellMinConfidence` now has teeth: a spell listed there never casts from
a running guess. It waits for the complete utterance and is judged at its own threshold, so
`irons_spellbooks:invisibility=0.7` is the knob for a spell that keeps getting force-fitted. That
costs a listed spell the wait for its final answer — about half a second after you stop speaking,
and it waits for you to stop, so say a listed spell on its own rather than in the middle of a
sentence. Everything not listed still casts the instant it is recognised. Reported against
Invisibility — thank you.

**Sound-alike matching now requires the two words to be about the same length.** Found while
chasing that report, and not its cause — across every Iron's Spells phrase nothing shares
Invisibility's sound-alike code, so that path could not have produced it — but a real weakness on
its own. The comparison that turns "fire boltz" back into "fire bolt" reduces any word to four
characters: a first letter and three consonant sounds. For a short name that is most of the word;
for a long one it is almost none of it, and everything past the fourth character was simply not
compared. Genuine near-misses are a character or two apart and still match; a word half the length
of the spell name no longer does.

**Voice volume scaling now earns the voice level bonus instead of shrinking your spellbook.**
`voiceVolumeScaling` used to promise "whisper for level 1, shout for your spellbook's level," and
in practice every spoken cast came out at level 1 however loudly you said it, because the volume
was being read after you had already stopped talking. It now means something better. The spell's
inscribed level — on the spellbook, the imbued item, or the scroll (level 1 under FREE) — is
always the floor; a voice cast never lands below it. `voiceLevelBonus` (or a player's
`playerAdvantages` level) is the most a spoken cast can add on top, and with `voiceVolumeScaling`
on, how much of it you earn depends on how loudly you said the spell: your normal speaking voice
earns about half, a raised voice all of it, a whisper none. The bonus always lands on a whole
level, never a fraction of one. Loudness is judged against your own voice: run
Config → More… → Calibrate mic once, speaking at your normal volume, and the mod remembers how
loud that is; until you do, it assumes a typical microphone. The extra levels are still charged at
the spell's ordinary mana cost. A spell repeated with the quick-recast key repeats at the loudness
it was originally said, and a queued spell fires at the loudness of the words that queued it. With
the option off, every spoken cast gets the full bonus, as before.

---

## Also

**Two server options that were never announced.** They went into 0.10.5 without a changelog line,
which is why people have been asking for them in the comments since:

- `incantationOnly` — `FIRST_CAST` makes each spell voice-cast once before it can be clicked, so
  learning the incantation unlocks it; `ALWAYS` means spells can only ever be spoken. Off by
  default. Players without the mod on their client are exempt rather than locked out.
- `voiceVolumeScaling` — scales the voice level bonus by how loudly you speak, as described
  above. Off by default, and it does nothing unless `voiceLevelBonus` (or a `playerAdvantages`
  level) is above 0.

Both live in `config/voicespells-server.toml`.

The store pages said Iron's Spells 'n Spellbooks was a **required** dependency. It never has been:
the mod loads and behaves sanely without it, finds no spells, and casts nothing, which is the
designed outcome and the reason addon spell mods work with no code of their own. Both pages now say
optional, so a launcher will stop insisting on it.
