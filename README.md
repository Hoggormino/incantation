# Iron's Spells: Incantation

**Cast Iron's Spells 'n Spellbooks spells by saying their names.** No training samples, no
keybind to remember — Incantation piggybacks on your Simple Voice Chat mic and runs an offline
speech recognizer (Vosk) restricted to a grammar of your installed spell names. Say `fireball`,
it casts fireball.

NeoForge 1.21.1.

## Requirements

- **Minecraft 1.21.1**
- **NeoForge 21.1.x**
- **Simple Voice Chat** (henkelmax, 2.5.x+)
- **Iron's Spells 'n Spellbooks** (iron431, 3.x+)
- A small Vosk English model (~40 MB) — one-time setup, see below

Simple Voice Chat and Iron's Spells must be present on both client and server. Curios is
optional but recommended (without it, the Curios spellbook slot can't be checked and the mod
degrades to hand-only).

## Setup

1. Drop `incantation-x.y.z.jar` into your `mods/` folder.
2. Download a Vosk model from <https://alphacephei.com/vosk/models>. The small English model
   (`vosk-model-small-en-us-0.15.zip`) is enough for vanilla spell names.
3. Unzip and copy the **contents** into `<minecraft>/config/voicespells/model/` so the layout is:
   ```
   config/voicespells/model/am/...
   config/voicespells/model/conf/...
   config/voicespells/model/graph/...
   ```
4. Launch the game and join a world. Make sure SVC is connected.
5. Equip a spellbook (or an imbued weapon), trigger SVC the way you normally would, and say the
   spell's name. Done.

For trickier spell names — Traveloptics, Cataclysm, anything with words the small model can't
pronounce — switch to the medium model (`vosk-model-en-us-0.22-lgraph`, ~128 MB) or remap with
custom phrases (see Configuration).

## What you get

- **Spellbook + imbued-weapon casting.** Either a spellbook in your Curios spellbook slot
  (default), a hand-held spellbook (with `castMode = ANY_SPELLBOOK`), or an imbued sword/staff
  in hand — Incantation finds the right item and casts via the matching `CastSource` so mana
  and cooldowns behave exactly like a manual cast.
- **HUD chip** showing your last cast, recent cast history, queued spells, miss toasts,
  the last phrase heard, and "did you mean…?" alias suggestions.
  Configurable corner, offset, opacity. Toggle with **B**.
- **Themes + palettes.** Cycle the accent color (Arcane, Blossom, Ocean, Mint, Gold, …) and
  switch the base palette between Dark, Midnight, and Slate. Some themes unlock as you
  voice-cast more.
- **Vanilla advancements.** Voice-cast milestones (1, 10, 50, 200, 1000) and combo casts surface
  as standard Minecraft advancements.
- **Spell List screen.** Browse every spell the registry exposed, with the exact phrase Vosk is
  listening for, your personal cast counts, last-cast times, and a school filter.
- **Voice Codex.** Personal stats — top-cast spell, average recognition latency, daily streak.
- **Test Arena.** Practice mode that records what *would* have cast without actually firing
  anything. Great for tuning aliases.
- **Welcome wizard** on first launch — talks you through model + mic + first cast.
- **Aliases / custom phrases.** Pick words the model can hear, bind them to a spell id.
- **Loadout shortcuts.** Say one word, cast the first castable spell from a list.
- **Voice commands.** `spell one`…`spell nine` switches the active spellbook slot (enable
  *voiceHotbarSelect* first — it is off by default). `yes`/`no`
  control the cast queue when hands-free confirm is on.
- **Server-side controls.** Per-player whitelist, blocklist, rate limit, broadcast-nearby,
  cast logging, follow subscriptions for admins.

## Keybinds

Rebind in Controls → Incantation. Defaults:

| Key | Action |
| --- | --- |
| **V** | Toggle voice casting (master on/off) |
| **B** | Toggle the HUD chip |
| **Y** | Accept the "did you mean X?" alias suggestion |
| **]** | Quick-recast the last spell |

## Configuration

Most settings have UI: **Mods → Incantation → Config** (or the More… screen for the rest).
The raw file is `config/voicespells-client.toml`; edits hot-reload without restarting.

Highlights:

- **Recognition** — fuzzy tolerance, substring match, dedup window, echo lockout, min
  confidence, per-spell confidence overrides.
- **HUD** — corner, offset, opacity, base palette, accent theme.
- **Modes** — trigger words, combat-only, pause-when-AFK, streamer mode, hands-free confirm.
- **Aliases** — extra phrases bound to spell ids.

Server settings live in `config/voicespells-server.toml`: cast mode (`CURIO_SPELLBOOK` /
`ANY_SPELLBOOK` / `FREE`), per-player whitelist, blocklist, rate limit, broadcast radius.

### Custom phrases

```toml
[recognition]
customPhrases = [
    "abyss blast=traveloptics:abyssal_blast",
    "dark beam=traveloptics:abyssal_blast",
]
```

Custom phrases override generated ones and are added to the Vosk grammar. Use the in-game spell
list or the debug monitor to find exact spell ids. No restart needed — use
**More… → Reload grammar now** and the recogniser picks them up immediately.

### Localisation / phrasebook

For bulk-translating every spell name to a non-English language, edit
`config/voicespells/phrasebook.json`. The file is auto-generated on first launch with one entry
per installed spell:

```json
{
  "_help": "Replace the 'override' value for each spell with the words you want to say…",
  "spells": {
    "irons_spellbooks:fireball": { "default": "fireball", "override": "" },
    "irons_spellbooks:firebolt":  { "default": "firebolt",  "override": "" }
  }
}
```

Set the `override` value to whatever you want to say for that spell; leave it empty to use the
English default. New spells installed later are appended automatically, and your existing
overrides are preserved across updates.

Vosk only recognises words from the loaded model's lexicon, so non-English overrides need a
same-language Vosk model — install one from <https://alphacephei.com/vosk/models> and point
`modelPath` at it in `voicespells-client.toml`.

## Troubleshooting

- **Chat says `Vosk model not found`** (or Diagnostics shows `Vosk model: FAIL`) — no model
  under `config/voicespells/model/`.
  Recheck the layout: `am/`, `conf/`, `graph/` should be directly under that path.
- **Mic indicator is dead** — SVC isn't transmitting. Confirm it works for chat first; Voice
  Spells listens to the same audio stream.
- **Spells aren't firing in-world** — open the More menu → Diagnostics. It runs a quick check
  on each prerequisite (model, SVC, spellbook detection).
- **Test Arena doesn't cast anything** — that's by design. The arena is a safe practice mode
  that records would-be casts without firing them. Close the screen to cast for real.
- **"Recognized but rejected"** — confidence too low. Lower the global threshold, or set a
  per-spell override.

## Credits

- **Vosk** — Alpha Cephei (Apache 2.0)
- **Simple Voice Chat API** — Maximilian Henkel (MIT)
- **Iron's Spells 'n Spellbooks** — iron431

## License

MIT.
