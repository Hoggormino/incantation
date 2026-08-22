# Iron's Spells: Incantation

**Say a spell's name. Cast it.**

Incantation captures your microphone directly, runs the audio through an offline speech
recognizer (Vosk), and casts whichever Iron's Spells 'n Spellbooks spell you just said. No voice
training, and the speech model installs itself on first launch.

---

## How it works

Incantation opens your microphone itself — no voice-chat mod involved — and feeds the audio
straight to a Vosk recognizer whose grammar is built from the spell names your installed mods
actually register. Anything that isn't a spell name resolves to "unknown" rather than snapping to
the nearest spell, so background noise doesn't fire anything.

Casting is then gated on what you actually have equipped: a spellbook in your Curios slot, or an
imbued weapon you're holding. Saying the name of a spell you don't have does nothing. Servers that
would rather let you cast from a spellbook held in hand can set `castMode = ANY_SPELLBOOK`.

Recognition runs entirely on your machine. No accounts, no telemetry, and your microphone audio is
never transmitted, never recorded, never written to disk. The one time Incantation touches the
network is the speech-model download on first launch; set `autoDownloadModel = false` and install
the model yourself if you would rather it didn't.

## When it listens

**By default the microphone is only open while you have a spellbook, staff or imbued weapon —
in either hand, or in your Curios slot.** Stow it and the mic closes. No keybind involved.

That means conversation with your spellbook stowed can never cast anything, which matters because
a lot of people run a voice-chat mod on the same microphone. **But if you keep a spellbook in your
Curios slot permanently, this is close to `ALWAYS_ON` in practice** — so if you talk to friends
mid-fight, use `HOLD_KEY_AND_ITEM`. Carrying a spellbook isn't on its own evidence you meant to
cast; that mode requires a held key too, so speech never reaches the recognizer unless you asked.

Set `gatingMode` to taste:

| Mode | Microphone is open |
|---|---|
| `HOLD_ITEM` | while a spell focus is in your hands **or Curios slot** *(default)* |
| `HOLD_KEY_AND_ITEM` | while holding the cast key **and** a spell focus |

| `HOLD_KEY` | while holding the cast key |
| `ALWAYS_ON` | whenever you are in a world — fully hands-free |

These gate **capture**, not recognition: in every mode but `ALWAYS_ON` the recognizer never
receives the audio, and the device is closed rather than merely ignored. The mic is also released
whenever the game is unfocused or paused.

The HUD's mic chip tells you which state you're in at a glance — the dot is dim when the mic is
closed, lit when it's open, and pulses while it's actually hearing you, with a live level meter
beside it.

## Features

- **Speak any indexed spell** — works with Iron's Spells, all its addons, and any mod that
  registers spells through Iron's API.
- **Equipped-only casting** *(default ON)* — a spell only fires if you actually have it on you.
  Anything else is rejected before it leaves your client.
- **Imbued weapons supported** — hold an imbued sword or staff, say the spell it carries, it casts
  via the same mechanism as right-clicking.
- **HUD chip** — corner-anchored chips showing a live mic indicator and level meter, plus last
  cast, recent cast history, queued spells, miss toast, the last phrase heard, and "did you
  mean…?" alias suggestions.
- **Vanilla advancements** — voice-cast milestones (1, 10, 50, 200, 1000) and combo casts surface
  as standard advancement toasts.
- **Spell List screen** — browse every spell the registry exposed with the exact phrase Vosk
  listens for, plus per-spell cast counts and school filtering.
- **Voice Codex** — personal stats (top-cast spell, daily streak, median latency).
- **Test Arena** — safe practice mode that records what *would* have cast without firing anything.
  Useful for tuning aliases or learning a tricky spell's pronunciation.
- **Welcome wizard** on first launch — walks you through mic + model + first cast.
- **Aliases & custom phrases** — bind words the model can pronounce to any spell id.
- **Play in your own language** — Spanish, Russian, French and German models are one config line
  away (`modelId`), and `config/voicespells/phrasebook.json` is generated with every installed
  spell and an editable `override` field, so you can translate the whole spell list in one file
  instead of adding aliases one at a time. Your overrides survive updates, and newly installed
  spells are appended automatically.
- **Loadout shortcuts** — say one word, cast the first castable spell from a list (cooldown + mana
  aware).
- **Voice commands** — say `no` to clear spells waiting in the cast queue, or `yes` to accept a
  "did you mean…?" alias suggestion (enable *handsFreeConfirm* first).
- **Server-side controls** — per-player whitelist, blocklist, rate limit, broadcast-nearby, cast
  logging.

## Requirements

- Minecraft **1.21.1** (NeoForge 21.1.x) or **1.20.1** (Forge 47.x)
- [Iron's Spells 'n Spellbooks](https://modrinth.com/mod/irons-spells-n-spellbooks) by iron431
- Curios API — recommended, and an Iron's Spells dependency anyway
- A microphone
- **No voice-chat mod required.** Incantation captures the microphone itself, and coexists with
  Simple Voice Chat if you use one to talk to friends.

## Setup

1. Drop the Incantation jar in your `mods/` folder alongside Iron's Spells.
2. Launch the game. On first run Incantation downloads `vosk-model-small-en-us-0.15` (~40 MB) into
   `config/voicespells/` for you.
3. Join a world, equip a spellbook (hand or Curios slot), and say a spell it carries.

Prefer to install the model yourself? Set `autoDownloadModel = false`, grab one from
[alphacephei.com/vosk/models](https://alphacephei.com/vosk/models), and unzip its **contents** into
`config/voicespells/model/` so you end up with `config/voicespells/model/am/`,
`config/voicespells/model/conf/`, etc.

For tricky spell names (Traveloptics, Cataclysm, anything with unusual phonetics), map an alias in
`config/voicespells-client.toml`:

```toml
customPhrases = [ "dark beam=traveloptics:abyssal_blast" ]
```

then use **More… → Reload grammar now** — no restart needed.

## Configuration

Most settings are accessible in-game from **Mods → Iron's Spells: Incantation → Config**.

- **Recognition tab** — gating mode, equipped-only restriction, fuzzy tolerance, substring match,
  dedup window, debug monitor.
- **HUD tab** — corner, offset, opacity.
- **More menu** — welcome wizard, Voice Codex, Diagnostics, Test Arena, reload grammar, profile
  export/import, auto-calibrate noise gate.

Client commands: `/voicespells devices` lists every capture device the game can see and which one
is selected — start there if the microphone looks dead.

Server settings (`config/voicespells-server.toml`): cast mode (`CURIO_SPELLBOOK` / `ANY_SPELLBOOK`
/ `FREE`), per-player whitelist, blocklist, rate limit, broadcast-nearby radius.

## Troubleshooting

- **Nothing happens when you speak** — check the gating mode first. By default the mic is only
  open while a spellbook, staff or imbued weapon is in your hands or Curios slot. The HUD mic dot
  tells you: dim means closed.
- **Chat says "Vosk model not found"** (or Diagnostics shows `Vosk model: FAIL`) — no model under
  `config/voicespells/model/`. Recheck the layout: `am/`, `conf/`, `graph/` should sit directly
  under that path.
- **Microphone looks dead** — run `/voicespells devices` to see what the game can find, and set
  `captureDevice` if the default isn't the one you want.
- **Recognition feels slow** — open More → **Auto-calibrate noise gate** and say a few spell names.
  Or use the **Diagnostics** screen to verify each prerequisite.
- **Test Arena doesn't cast** — by design. It records would-be casts so you can practice safely.

## License

MIT.

## Credits

- **Vosk** — [Alpha Cephei](https://alphacephei.com/vosk/) (Apache 2.0)
- **Iron's Spells 'n Spellbooks** — iron431
- **Spanish phrasebook** — NeoTargetStudios (Spidercat0926), who translated close to 300 spell
  names across nine spell mods and helped with the mod itself
