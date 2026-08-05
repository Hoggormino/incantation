# Iron's Spells: Incantation

**Say a spell's name. Cast it.**

Incantation listens to your Simple Voice Chat mic, runs the audio through an offline speech
recognizer (Vosk), and casts whichever Iron's Spells 'n Spellbooks spell you just said. No
voice training, no extra keybind, and the speech model installs itself on first launch.

---

## How it works

When Simple Voice Chat is transmitting your audio, Incantation feeds the frames to a Vosk
recognizer built from the names of every spell in the registry. Whatever it hears is then
checked against what you actually have equipped — a spellbook in your Curios slot, or an
imbued weapon you're holding — and anything else is rejected on your client before it ever
reaches the server. Servers that would rather let you cast from a spellbook held in hand
can set `castMode = ANY_SPELLBOOK`.

The grammar deliberately stays broad. Narrowing it to just your equipped spells sounds
safer, but with only two or three phrases left the recognizer force-matches *any* audio —
a cough, a footstep, someone else talking — into whichever of them is closest. Keeping
every spell name in there dilutes that, and the equipped check is what actually stops an
unowned spell from casting.

Recognition runs entirely on your machine. No accounts, no telemetry, and your voice never
leaves your PC — it is never transmitted, never recorded, never written to disk. The one
time Incantation touches the network is the speech-model download on first launch; set
`autoDownloadModel = false` and install the model yourself if you would rather it didn't.

## Features

- **Speak any indexed spell** — works with Iron's Spells, all its addons, and any mod that
  registers spells through Iron's API.
- **Equipped-only casting** *(default ON)* — a spell only fires if you actually have it on
  you. Under the default server `castMode = CURIO_SPELLBOOK` that means your Curios spellbook
  slot or an imbued weapon in hand; set `ANY_SPELLBOOK` to also allow a spellbook held in
  hand or on the hotbar.
- **Imbued weapons supported** — hold an imbued sword or staff, say the spell it carries,
  it casts via the same mechanism as right-clicking.
- **HUD chip** — corner-anchored chip showing last cast, queued spells, miss toast, the
  last phrase heard, and "did you mean…?" alias suggestions.
- **Themes** — accent presets (Arcane, Blossom, Ocean, Dusk, Mint, Gold, plus Phoenix / Frost /
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
- **Play in your own language** — `config/voicespells/phrasebook.json` is generated with every
  installed spell and an editable `override` field, so you can translate the whole spell list in
  one file instead of adding aliases one at a time. Your overrides survive updates, and newly
  installed spells are appended automatically. Speaking a language other than English also needs
  a matching [Vosk model](https://alphacephei.com/vosk/models) — Vosk can only hear words that
  are in the loaded model's lexicon.
- **Loadout shortcuts** — say one word, cast the first castable spell from a list (cooldown
  + mana aware).
- **Voice commands** — `spell one`…`spell nine` switches the spellbook slot without casting
  (enable *voiceHotbarSelect* first); `yes`/`no` controls the cast queue.
- **Server-side controls** — per-player whitelist, blocklist, rate limit, broadcast-nearby,
  cast logging.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) by henkelmax (2.5.x+)
- [Iron's Spells 'n Spellbooks](https://modrinth.com/mod/irons-spells-n-spellbooks) by
  iron431 (3.x+)
- A Vosk English model — downloaded for you on first launch (~128 MB)
- Curios API recommended (Iron's Spells dependency anyway)

## Setup

1. Drop the Incantation jar in your `mods/` folder alongside SVC and Iron's Spells.
2. Launch the game. On first run Incantation downloads `vosk-model-en-us-0.22-lgraph`
   (~128 MB) into `config/voicespells/model/` for you.
3. Join a world, trigger SVC, and say a spell you have equipped.

Prefer to install the model yourself? Set `autoDownloadModel = false`, grab one from
[alphacephei.com/vosk/models](https://alphacephei.com/vosk/models), and unzip its
**contents** into `config/voicespells/model/` so you end up with
`config/voicespells/model/am/`, `config/voicespells/model/conf/`, etc.
`vosk-model-small-en-us-0.15` (~40 MB) is enough for vanilla spell names.

For tricky spell names (Traveloptics, Cataclysm, anything with unusual phonetics), map an
alias in `config/voicespells-client.toml`:

```toml
customPhrases = [ "dark beam=traveloptics:abyssal_blast" ]
```

then use **More… → Reload grammar now** — no restart needed.

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

- **Chat says "Vosk model not found"** (or Diagnostics shows `Vosk model: FAIL`) — no model
  under `config/voicespells/model/`. Recheck the layout: `am/`, `conf/`, `graph/` should sit
  directly under that path.
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
