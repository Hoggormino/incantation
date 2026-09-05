# 0.10.6

Everything since 0.10.5.

Two bugs from the CurseForge comments, both of the same unpleasant kind: the mod heard you
perfectly and then quietly did the wrong thing about it.

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

**A spell could be matched from words that sound nothing like it.** When the mod cannot match what
it heard exactly, it falls back through near-miss matching to a sound-alike comparison — the thing
that turns "fire boltz" back into "fire bolt". That last comparison reduces any word to four
characters: a first letter and three consonant sounds. For a short name that is most of the word.
For a long one it is almost none of it, and everything past the fourth character was simply not
being compared, so a long spell name would collect unrelated words that happened to start the same
way.

Sound-alike matching now also requires the two to be about the same length before it will trust
them. Genuine near-misses are a character or two apart and still match; a word half the length of
the spell name no longer does. Reported against Invisibility, which is long enough to be the worst
case of it — thank you.

**Voice volume scaling capped every cast at level 1.** `voiceVolumeScaling` is a server option
that is meant to make a whispered spell cast at level 1 and a shouted one at the full level of your
spellbook. Turning it on did the first half only: every spoken cast came out at level 1, however
loudly you said it. The volume was being read at the moment the cast was sent, and most casts are
sent on the recogniser's final answer — which arrives after you have stopped talking and the
microphone gate has closed, when the level has already fallen back to silence. The mod now keeps
the loudest moment of the utterance and sends that. A spell repeated with the quick-recast key
repeats at the volume it was originally said, as it was always supposed to.

---

## Also

**Two server options that were never announced.** They went into 0.10.5 without a changelog line,
which is why people have been asking for them in the comments since:

- `incantationOnly` — `FIRST_CAST` makes each spell voice-cast once before it can be clicked, so
  learning the incantation unlocks it; `ALWAYS` means spells can only ever be spoken. Off by
  default. Players without the mod on their client are exempt rather than locked out.
- `voiceVolumeScaling` — the whisper-to-shout level scaling described above, now working. Off by
  default.

Both live in `config/voicespells-server.toml`.

The store pages said Iron's Spells 'n Spellbooks was a **required** dependency. It never has been:
the mod loads and behaves sanely without it, finds no spells, and casts nothing, which is the
designed outcome and the reason addon spell mods work with no code of their own. Both pages now say
optional, so a launcher will stop insisting on it.
