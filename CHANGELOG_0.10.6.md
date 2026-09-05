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

---

## Also

The store pages said Iron's Spells 'n Spellbooks was a **required** dependency. It never has been:
the mod loads and behaves sanely without it, finds no spells, and casts nothing, which is the
designed outcome and the reason addon spell mods work with no code of their own. Both pages now say
optional, so a launcher will stop insisting on it.
