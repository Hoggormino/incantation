# Incantation 0.10.0 — no voice-chat mod required

**Incantation now captures your microphone itself.** Simple Voice Chat is no longer a
dependency. The mod opens the mic through OpenAL, which Minecraft already ships, so voice
casting works on its own — and still works alongside SVC if you use it to talk to friends.

**This changes your setup.** Read the two points below before updating.

## The microphone is gated now

By default the mic is only open **while you're holding a spellbook, staff or imbued weapon** —
in either hand, or in your Curios slot. Stow it and the mic closes. There's no keybind involved.

Set `gatingMode` in `config/voicespells-client.toml` if you want something else:

| Mode | Microphone is open |
|---|---|
| `HOLD_ITEM` | while a spell focus is in your hands or Curios slot *(default)* |
| `HOLD_KEY_AND_ITEM` | while holding the cast key **and** a spell focus |
| `HOLD_KEY` | while holding the cast key |
| `ALWAYS_ON` | whenever you're in a world — fully hands-free |

If you talk to friends over voice chat **while armed**, use `HOLD_KEY_AND_ITEM`. Carrying a
spellbook isn't on its own evidence you meant to cast, and with a book parked in your Curios slot
`HOLD_ITEM` is close to always-on in practice.

The HUD mic dot tells you which state you're in: dim means closed, lit means open, and it pulses
while it's actually hearing you.

## Now on Minecraft 1.20.1

Alongside 1.21.1. Forge 47.x for 1.20.1, NeoForge 21.1.x for 1.21.1 — one codebase.

## Also in this release

- **The speech model installs itself** on first launch (~40 MB). Spanish, Russian, French and
  German models are one `modelId` line away.
- **Spell names the model can't pronounce are respelled automatically.** Vosk can only hear words
  in its lexicon, and a spell whose name is missing was silently uncastable. Names that can't be
  rescued are now named in the log instead of failing quietly.
- **Custom phrases no longer need a restart** — use **More… → Reload grammar now**.
- **`/voicespells devices`** lists every capture device and which one is selected.

## Fixed

- **Clients without Simple Voice Chat crashed on startup.** SVC was declared optional, but the
  mod touched a class that couldn't load without it, so mod loading died with
  `NoClassDefFoundError` instead of idling. This affected 0.9.5 and earlier. Moot now that SVC is
  gone, but it's why 0.9.5 should not be used.
- **The microphone opened briefly on the title screen**, contradicting the setting that says it
  doesn't.
- Documentation that described features the code doesn't have: there is no macro recorder, the
  combat-only gate never counted damage you dealt, `ANY_SPELLBOOK` also accepts hotbar slots, and
  there is no "Light" palette.

## Requirements

- Minecraft 1.21.1 (NeoForge 21.1.x) **or** 1.20.1 (Forge 47.x)
- Iron's Spells 'n Spellbooks
- Curios API (an Iron's Spells dependency anyway)
- A microphone. **No voice-chat mod.**
