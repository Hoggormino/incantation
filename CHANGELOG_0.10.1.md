# Incantation 0.10.1 — Forge names the conflicting mod

A small release with one fix, and it only affects **Forge 1.20.1** players.

## Fixed

**Forge now tells you which mod broke a cast.**

When another mod hooks Iron's Spells' cast path and throws, Incantation performs its casts through
reflection, so the exception arrives wrapped. The player saw only:

```
Cast error: InvocationTargetException
```

which names nothing useful and looks like an Incantation bug. It isn't — it's a conflict in the
other mod — but there was no way to tell that from the message. One reported case took weeks to
trace.

NeoForge started naming the responsible mod in 0.9.5. The Forge build was left out of that change,
so 1.20.1 players kept getting the useless message. They now get the same thing NeoForge players do:

```
Cast blocked by Iron's Restrictions (irons_restrictions) - mod conflict, see the log
```

with the full stack trace and an explanation in the log. This doesn't fix the underlying conflict —
that isn't Incantation's to fix — but it turns a week of guesswork into a one-line answer.

## Should you update?

- **On Forge 1.20.1** — yes, if you run other spell-related mods.
- **On NeoForge 1.21.1** — no reason to. Nothing else changed; you already had this in 0.9.5.

## Requirements

Unchanged from 0.10.0:

- Minecraft 1.21.1 (NeoForge 21.1.x) **or** 1.20.1 (Forge 47.x)
- Iron's Spells 'n Spellbooks
- Curios API (an Iron's Spells dependency anyway)
- A microphone. **No voice-chat mod.**
