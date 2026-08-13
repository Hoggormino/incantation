# Incantation 0.10.3 — the things that were quietly not working

A long bug-hunting pass over the whole mod. Most of what follows was **silently** broken: no crash,
no error, nothing in the log — features that simply did nothing while looking like they worked.

If you are on 1.21.1, the first item alone is worth updating for.

## Your settings actually save now (1.21.1)

On NeoForge, **nothing the config screen wrote ever reached the disk.** Not the HUD corner, the
offsets, the opacity, the theme, the recognition tuning — and not aliases you added, profiles you
imported, or noise-gate calibration either.

It behaved perfectly all session, which is why it went unnoticed for so long. Everything came back
to defaults on the next launch.

Forge 1.20.1 was never affected.

## Trigger words no longer switch casting off

If you set a **trigger word** — say, `cast`, so only "cast fireball" fires — voice casting stopped
working entirely. Not for that word: for everything.

The recogniser can only hear words it has been given, and the trigger word was never added to its
vocabulary, so the check for it could never pass. No log line, no "miss" on the HUD. It just went
quiet, and the only way back was clearing the setting.

Trigger words that are *also* part of a spell's name now work too, which matters because the
config file's own example suggests `summon` — and that used to break every Summon spell.

## Your stats are no longer at risk

A single unreadable read of `stats.dat` — an antivirus lock, a cloud-sync hiccup — used to be
enough to wipe your lifetime totals, unlocked themes and codex history. The failure was logged and
then the next save wrote zeros over the real file.

Stats are now written to a temporary file and moved into place, so an interrupted save cannot leave
a half-written file behind, and a file that cannot be read is preserved rather than overwritten.

## Scrolls are consumed when you voice-cast them

**A balance fix, and it is a nerf.** Voice-casting from a scroll was not using it up, so a
single-use item could be cast from indefinitely. Right-clicking always consumed it correctly; only
voice casting was wrong.

## Casting now respects what you are holding

Spells could be voice-cast from a spellbook, imbued weapon or scroll sitting **anywhere in your
hotbar**, including in the strictest cast mode. It now requires the item in your main hand or off
hand, which is what the settings describe and what right-clicking has always required.

## Things that had never worked once

- **The cooldown indicator** under the crosshair. It had never drawn, on any version.
- **The Test Arena, the Live Monitor, the first-run mic check and auto-calibrate** all opened your
  microphone and then threw away every frame. Auto-calibrate would tell you it heard nothing after
  five seconds of talking — and then overwrite your noise-gate setting with a worse one.
- **The HUD in the top-right or bottom-right corner.** The whole thing rendered off the edge of the
  screen.
- **`/voicespells diag`** reported "No voice casts logged this session" on any default server, while
  casts were plainly happening.
- **Diagnostics** reported `Vosk model — FAIL` on a perfectly healthy install, because it was
  looking in the wrong directory.

## Forge 1.20.1: casting on servers without the mod

Forge clients could not voice-cast on a server that has Iron's Spells but not Incantation. The
fallback path that NeoForge used was assumed impossible on 1.20.1; it was not. Spells were being
silently dropped.

## Also fixed

- A long chat message with the rank tag enabled could exceed the chat limit and **disconnect you**.
- A failed speech-model load spawned loader threads about ten times a second, forever.
- An interrupted model download left a half-extracted folder that looked valid, so it never retried
  and recognition stayed dead across restarts.
- Turning **Only owned spells** off left the recogniser still restricted to whatever you had
  equipped when you turned it on.
- Loadouts skipped their remaining spells if the first one was not equipped, and could drop out of
  the recogniser's vocabulary entirely.
- The leaderboard behind `/voicespells top` no longer trusts a number sent by the client.
- Cancelling the config screen no longer leaves a previewed theme applied.
- The Diagnostics list no longer appears frozen when scrolling back up.
- `[hud] opacity` now affects the cast toast, which it never did.
- Removed `/voicespells rank`, which printed no rank, and the server-side `/voicespells reload`,
  which reloaded nothing and shadowed the client command that does.
