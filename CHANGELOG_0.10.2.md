# Incantation 0.10.2 — servers stop turning players away

Mostly server-side fixes, and two of them matter whether or not you noticed anything was wrong.

## Your server no longer requires the mod on clients

A server running Incantation was rejecting **every player who did not also have it installed** —
with an "Incompatible" screen on NeoForge, and a bare "Connection closed - mismatched mod channel
list" on Forge. It also advertised itself as incompatible in the multiplayer list, so people saw a
red X before they even tried.

Nothing this mod sends is client-bound, so that requirement bought a server nothing.

Players **with** Incantation voice-cast exactly as before. Players **without** it now join and play
normally — they simply cannot voice-cast. That is the only thing they lose.

To voice-cast yourself you still need it on both sides: the client owns the microphone, the server
validates and performs the cast.

## Server config controls were being ignored on NeoForge

`serverBlockedSpells`, the allowlist, `maxCastsPerSecond` and `castMode` had no effect on voice
casts on NeoForge. An operator could ban a spell, see it listed as banned in the config, and have
it voice-cast anyway. The `voice_cast` advancements, the admin log and `/voicespells follow` were
skipped on the same path.

The cause: voice casts took Iron's Spells' own packet, so this mod's server side never saw them.
Forge was unaffected. If you run a NeoForge server and set any of those options, **they start
working in this release** — which may change behaviour you had grown used to.

The fast path still exists for what it was built for: casting on a server that does not have
Incantation, where there is no server-side config to apply in the first place.

## Fixed

- **The first-run mic check could never see your microphone.** The wizard runs at the title screen,
  and a fix in 0.10.0 that stopped the mic opening there also stopped it opening for the check
  itself — so it said "Talk into your mic now" while guaranteeing silence.
- **A modified client could disconnect itself** with an "Internal Exception" and a stack trace in
  the server log, by sending a malformed spell id. NeoForge only; Forge already handled it.
- **Cooldown and "is casting" hints never worked.** They resolved against an Iron's Spells class
  that does not exist, so every lookup failed silently and every caller quietly used its fallback.
- **Server-side state was never released.** Cast history, follow subscribers and the leaderboard
  persisted for the life of the process. On single-player that meant one world's data leaking into
  the next one you opened.
- `pack_format` on 1.21.1 was a version that does not exist.

## Changed

- **The casting screen edges are off by default.** The accent bars and corner glows drawn during a
  long cast have never actually rendered in any released version — the reflection behind them was
  broken from the start. Fixing that switched them on for the first time, which is not a change
  anyone asked for, so they are now `castVignette` in the client config, default off. Turn it on if
  you want them.

## Requirements

Unchanged:

- Minecraft 1.21.1 (NeoForge 21.1.x) **or** 1.20.1 (Forge 47.x)
- Iron's Spells 'n Spellbooks
- Curios API (an Iron's Spells dependency anyway)
- A microphone. **No voice-chat mod.**
