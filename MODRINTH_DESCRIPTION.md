# Iron's Spells: Incantation

**Say a spell's name. Cast it.**

Incantation listens to your Simple Voice Chat mic, runs the audio through an offline speech
recognizer (Vosk), and casts whichever Iron's Spells 'n Spellbooks spell you just said. No
voice training, no extra keybind, no setup beyond unzipping a model into the config folder.

---

## How it works

When Simple Voice Chat is transmitting your audio, Incantation feeds the frames to a Vosk
recognizer whose grammar is restricted to the names of spells you actually have equipped
(spellbook in your Curios slot, spellbook in hand, or an imbued weapon you're holding).
That means the recognizer literally cannot mis-hear you and accidentally fire a spell you
don't have — background chatter just doesn't match anything in the grammar.

The whole pipeline runs locally. No network calls. No accounts. Your voice never leaves
your PC.

## Features

- **Speak any indexed spell** — works with Iron's Spells, all its addons, and any mod that
  registers spells through Iron's API.
- **Owned-spell restriction** *(default ON)* — Vosk's grammar dynamically narrows to the
  spells in your Curios slot, hands, and hotbar. Swap spellbooks and the grammar refreshes
  within ~2 seconds.
- **Imbued weapons supported** — hold an imbued sword or staff, say the spell it carries,
  it casts via the same mechanism as right-clicking.
- **HUD chip** — corner-anchored chip showing last cast, queued spells, miss toast, "did
  you mean…?" alias suggestions, and a live audio meter.
- **Themes** — accent presets (Arcane, Blossom, Ocean, Mint, Gold, plus Phoenix / Frost /
  Verdant / Necrotic unlocked by cast milestones) and base palettes (Dark, Midnight, Slate).
- **Vanilla advancements** — voice-cast milestones (1, 10, 50, 200, 1000) and combo casts
  surface as standard advancement toasts.
- **Spell List screen** — browse every spell the registry exposed with the exact phrase Vosk
  listens for, plus per-spell cast counts and school filtering.
- **Voice Codex** — personal stats (top-cast spell, daily streak, median latency).
- **Test Arena** — safe practice mode that records what *would* have cast without firing
  anything. Useful for tuning aliases or learning a tricky spell's pronunciation.
- **Welcome wizard** on first launch — walks you through mic + model + first cast.
- **Aliases & custom phrases** — bind words the model can pronounce to any spell id.
- **Loadout shortcuts** — say one word, cast the first castable spell from a list (cooldown
  + mana aware).
- **Macros** — record a cast sequence, replay it with one keybind.
- **Voice commands** — `spell one`…`spell nine` switches the spellbook slot without casting;
  `yes`/`no` controls the cast queue.
- **Server-side controls** — per-player whitelist, blocklist, rate limit, broadcast-nearby,
  cast logging.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) by henkelmax (2.5.x+)
- [Iron's Spells 'n Spellbooks](https://modrinth.com/mod/irons-spells-n-spellbooks) by
  iron431 (3.x+)
- A small Vosk English model (~40 MB)
- Curios API recommended (Iron's Spells dependency anyway)

## Setup

1. Drop `incantation-0.9.0.jar` in your `mods/` folder alongside SVC and Iron's Spells.
2. Grab a Vosk model from [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models)
   — `vosk-model-small-en-us-0.15.zip` is plenty for vanilla spell names.
3. Unzip and copy the **contents** of that folder into `config/voicespells/model/` so you
   end up with `config/voicespells/model/am/`, `config/voicespells/model/conf/`, etc.
4. Launch the game, join a world, trigger SVC, and say a spell you have equipped.

For tricky spell names (Traveloptics, Cataclysm, anything with unusual phonetics), either:

- switch to the medium model (`vosk-model-en-us-0.22-lgraph`, ~128 MB), or
- map an alias: in `config/voicespells-client.toml`,
  ```toml
  customPhrases = [ "dark beam=traveloptics:abyssal_blast" ]
  ```
  then restart.

## Configuration

Most settings are accessible in-game from **Mods → Iron's Spells: Incantation → Config**.

- **Recognition tab** — owned-spell restriction, fuzzy tolerance, substring match, dedup
  window, debug monitor.
- **HUD tab** — corner, offset, opacity, base palette, accent theme.
- **More menu** — welcome wizard, Voice Codex, Diagnostics, Test Arena, profile
  export/import, auto-calibrate noise gate.

Server settings (`config/voicespells-server.toml`): cast mode
(`CURIO_SPELLBOOK` / `ANY_SPELLBOOK` / `FREE`), per-player whitelist, blocklist, rate limit,
broadcast-nearby radius.

## Troubleshooting

- **HUD says "loading model"** — Vosk hasn't found a model under `config/voicespells/model/`.
  Recheck the layout: `am/`, `conf/`, `graph/` should sit directly under that path.
- **Mic indicator is dead** — SVC isn't transmitting. Confirm SVC works for chat first.
- **Recognition feels slow** — open More → **Auto-calibrate noise gate** and say a few
  spell names. Or use the **Diagnostics** screen to verify each prerequisite.
- **Test Arena doesn't cast** — by design. It records would-be casts so you can practice
  safely.

## License

MIT.

## Credits

- **Vosk** — [Alpha Cephei](https://alphacephei.com/vosk/) (Apache 2.0)
- **Simple Voice Chat API** — Maximilian Henkel (MIT)
- **Iron's Spells 'n Spellbooks** — iron431
