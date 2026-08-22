# 0.10.5

0.10.4 was never published, so if you are coming from 0.10.3 this contains both.

This release is mostly repair. Several settings did nothing, one of them blocked casting entirely
whenever it was switched on, and the HUD had been drawing text with no chip behind it for anyone
who had not hand-edited their config. Those are fixed. There is also the beginning of proper
support for playing in a language other than English.

---

## Fixed

**Combat only blocked every cast.** Turning it on stopped voice casting completely — even while
something was hitting you — and it said nothing when it did, so it looked like a dead microphone.
It was reading a value the client is never given. It now works from damage you have actually taken
and from hostiles actually near you, so opening a fight with a spell works.

**Blocked casts are no longer silent.** Combat-only, AFK and sneak-to-cast all used to reject
without a word. If a gate stops a cast, the HUD now says which one.

**The cast queue could not hold more than one spell.** A queued spell's short life was counted from
the moment you said it rather than from when the spell in front of it finished, so naming a second
spell during a long cast always lost it. Queue sizes above 1 do something now.

**Pause when AFK did not pause anything** — the microphone stayed open and kept listening. It now
releases the device. It also treated standing still as being away, so mining, fishing or holding a
position in a farm counted as AFK; looking around, swinging and using items now count as activity.

**Hands-free confirm's "yes" did nothing.** It now accepts the "did you mean…?" alias suggestion,
which is what its description always claimed.

**The config screen crashed the game on Forge 1.20.1.** Opening it was fatal. It has been unopenable
on that loader for as long as the current UI has existed.

**Selecting a different speech model did nothing** for anyone who had installed the mod before the
model list existed — you were kept on English no matter what you chose. Relatedly, pointing
`modelPath` at an empty folder downloaded the *English* model into it rather than the one you
wanted.

**Voice-cast advancements could not be earned**, because cast totals were never saved. Streak
milestones were also taken on trust from the client; the server counts them itself now.

**Assorted:** text that ran off the edge of panels in a dozen places, the device picker eating the
words "System default", the Voice Codex drawing its last row outside its own frame, and a leaked
network connection on every failed model download.

---

## Playing in another language

If you load a non-English speech model today, nothing happens at all — no error, no warning. That is
not a broken model: spell phrases are English words, and the recogniser silently discards any phrase
containing a word its model does not know. Against a Russian model, that is every phrase.

The mod now **tells you** instead of failing in silence. It counts the phrases the loaded model
cannot pronounce, warns in the log, and puts a line in chat when you join a world. `/voicespells
vocab` lists the specifics.

Translating the spell list is still what makes it work, and there are now two head starts in the
repository:

- **Spanish** — 293 of 294 phrases, contributed by **NeoTargetStudios (Spidercat0926)**, including
  188 spells from addon mods that ship no translations in any language.
- **Russian** — 106 phrases filled in automatically from Iron's Spells' own Russian translation,
  leaving the addon spells for a contributor.

Neither is bundled in the jar yet; both live in `contrib/` and can be copied into
`config/voicespells/phrasebook.json` by hand. A tool for generating the same head start for any
language Iron's Spells translates is in `tools/`.

**Spell names now appear in your own language** on every screen and on the HUD.

---

## Removed

**Voice hotbar select.** It added nine phrases to the recogniser's vocabulary, and every extra
phrase is another thing a spell name can be misheard as — a real accuracy cost for a feature that
only changed a selection. It could not work at all on a non-English model, and its own settings
description told you to say "slot one" while the code was listening for "spell one".

---

## Interface

The screens are built from Minecraft's own widgets and textures now rather than hand-drawn
imitations, so a GUI resource pack reaches them.

- **HUD chips have a background.** The queue chip, miss toast, last-heard chip and alias prompt have
  all been bare text lying on the world unless you had hand-edited your colours.
- **The cooldown indicator is out of the middle of the screen.** It used to sit under the crosshair;
  it is now a chip in the HUD corner you chose, with an on/off switch and a vertical offset on the
  HUD tab.
- **New Credits screen**, with the libraries the mod uses and their licences.
- Tabs, scrollbars, list rows and buttons are vanilla; text no longer overflows its panels.

---

## Known limitations

Said plainly so nobody has to discover them:

- **French and Russian are unproven.** Iron's Spells translates most of its spell names into both,
  but nobody has yet spoken them into a matching speech model with this mod.
- **German cannot work yet.** Iron's Spells ships no German translation, so there are no German
  spell names to work from. It needs a hand-written phrase pack, not a different model.
- **Addon spells have no translations in any language**, so on a modpack a non-English player
  depends on a hand-written phrasebook.
- **Pause when AFK still works from movement and input**, not presence. Sitting still and silent
  past the timeout will still pause it.
- **Sneak to cast gives no feedback** beyond the miss toast, and holding sneak while mounted
  dismounts you — that is vanilla behaviour, not something the mod can change.
