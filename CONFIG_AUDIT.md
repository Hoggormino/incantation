# Config audit — what to keep, merge and delete

57 options were reviewed across the client and server configs. 32 earn their place
unchanged. The rest are below, with what breaks if they go.

One of these turned out to be a **live bug**; another was struck from the delete list
entirely instead of being removed — see the notes.


## Delete (5)

| Option | Side | Why |
|---|---|---|
| `colors.background (bgColor)` | client | Default is "00000000" - fully transparent - so on every unmodified install this option draws literally nothing. It is a leftover of the panelled chip the panelless redesign removed, and it is the reason drawChip has to do the four-edge-rectangle dance instead of a fill. Cutting it means anyone who h |
| `colors.border (borderColor)` | client | Same story as background: default "00000000", so no chip outline is drawn on a default install. Two toml colour keys that render nothing are two support questions ("why doesn't changing the colour do anything") for zero visible feature. Cutting it removes the outline for anyone who set one by hand;  |
| `recognition.sassMode` | client | It is a joke option that sabotages the feature it rides on. It only fires inside `if (cShowMisses)`, and showMisses exists precisely so the player can read the raw phrase that failed - so a third of the time the diagnostic is replaced with 'The arcane shrugs.' It is toml-only, off by default, and no |
| `recognition.combatOnly` | client | It cannot work as written, so turning it on silently disables voice casting entirely. isInCombat() compares level().getGameTime() - world age, millions of ticks in any real save - against getLastHurtByMobTimestamp(), which vanilla assigns from the entity's own tickCount (verified in LivingEntity byt |
| `internal.firstRun` | client | It is a duplicate of a latch that already works better. The wizard pops only when firstRun AND !VoiceStats.wizardSeen(), and stats.dat is written immediately while the toml write is the one that historically failed - the code comment says outright that 'the stats latch is the only reason the wizard  |

## Merge into another option (6)

| Option | Side | Why |
|---|---|---|
| `recognition.dedupMillis` | client | It is inert at the defaults. lastDispatchedNanos and lastDispatchedFirstNanos are assigned the same value at all three dispatch sites and neither slides afterwards (the code comments now explicitly forbid sliding), so inSlidingWindow and inEchoLockout measure from an identical anchor and the effecti |
| `recognition.modelPath` | client | Two options answer one question - 'which model'. resolveModelDir already treats them as a single decision with modelPath as the short-circuit, and the modelId comment has to end with 'modelPath overrides this entirely', which is the tell. Collapse to one key that takes either a catalogue id or a pat |
| `recognition.triggerWord` | client | It is already merged in code and nowhere else. cacheColors folds it into the cTriggerWords set and nothing ever reads cTriggerWord again - the field is written once and never consulted. It exists only as a migration shim for pre-triggerWords configs. Fold any non-blank value into triggerWords on loa |
| `recognition.pauseWhenAfk` | client | A boolean plus a duration where the duration alone can express both. afkSeconds already has a floor of 5 and a ceiling of 3600; make 0 mean off and this key disappears, taking one toggle and one slider off the Behaviour tab and one invalid combination (pauseWhenAfk=true, afkSeconds=3600) out of the  |
| `recognition.incantations` | client | Identical semantics to customPhrases by the code's own admission - same parser, same map, same precedence, and the difference is described as user-facing tidiness in the toml. Worse, it is half-wired: AddAliasScreen reads both lists but only ever writes to customPhrases, so a phrase added in-game ca |
| `casting.broadcastRadius` | server | A sub-knob that is only read when its parent boolean is on, and whose 0 value means almost the same thing as the parent being off. Collapse to one integer key on broadcastVoiceCasts (0 or -1 = off, N = radius). That removes one option, one always-paired combination from the test matrix, and the clas |

## Keep, but leave in the toml rather than the UI (14)

| Option | Side | Why |
|---|---|---|
| `colors.textMuted` | client | It is genuinely read on three chips and is the only lever for players whose background makes grey unreadable, but it is a raw 8-char ARGB string. That belongs in the toml, not on a tab. It is already toml-only; leave it there and never promote it. |
| `colors.toast (textToast)` | client | Its scope shrank when the cast toast moved to per-school hues - it now only colours the queued chip. Still read, still toml-appropriate, but its comment overstates what it controls and should be corrected to say 'queued-cast chip'. |
| `recognition.debugMonitor` | client | The feature it names no longer exists - the Live Monitor became its own screen reachable unconditionally from More..., so the flag gates nothing but log level. Keep the behaviour (a log-verbosity switch is worth having for support), but the toml comment is now |
| `recognition.clientPreflight` | client | Nobody wants this off in normal play - it exists as an escape hatch for when Iron's Spells' client-side reflection goes stale after an update and the preflight starts rejecting valid casts. That is a support-ticket lever, not a player setting. It is already to |
| `recognition.autoDownloadModel` | client | A ~40MB automatic download is exactly the thing some players and most server packs want to refuse, and the code already logs a clear message and explains manual install when it is off. But it is a one-time install decision, not a play setting - toml is right. |
| `recognition.perSpellMinConfidence` | client | Solves a real problem the global floor cannot (one stubborn spell name) and is parsed defensively. But it is an id=float list - a text field on a settings tab would be worse than the toml. Note the interaction worth documenting: a non-empty list disables the c |
| `recognition.enableEchoSfx` | client | Genuine audio feedback and a genuine annoyance depending on the player, read at all three dispatch sites. But it is a one-decision cosmetic toggle and the HUD tab is already eight rows; toml (where it lives now) is right. |
| `recognition.alwaysShowHeard` | client | It is a tuning display, and the tuning display now has a whole screen of its own - the Live Monitor, which shows the same stream with confidence and match tier. Keeping a permanently-on-screen duplicate as a UI toggle next to 'Show misses' invites players to t |
| `recognition.handsFreeConfirm` | client | Half of it is admitted dead - the code comment says 'yes' is a no-op because the queue auto-drains - and the live half only matters when castQueueSize > 1. Meanwhile turning it on injects two of the most common words in English into a grammar-mode recogniser t |
| `recognition.noiseGateRms` | client | Load-bearing - it is what stops grammar-mode Vosk hallucinating spells out of breath and room tone - but it is a raw 0..32767 RMS number nobody can pick by hand. It already has the right UI: the Calibrate button on the More screen measures it. Keep the key in  |
| `recognition.suspendWhenUnfocused` | client | Read in two places and defaulted correctly. The only reason to turn it off is 'I want to cast while tabbed out', which is a niche nobody arrives at by browsing a tab - and turning it off means holding the OS microphone open the whole session, which is the exac |
| `recognition.grammarFloor` | client | This is the mod's main accuracy safety valve - a grammar of two phrases turns any sound into a spell - and its comment is the best-written in the file. But 1..512 with a correct answer around 16 is a maintainer's dial, not a player's. Toml is exactly right and |
| `recognition.chatRankTag` | client | Purely cosmetic, off by default, and it rewrites the player's outgoing chat - including a length guard that silently drops the tag near the 256-char cap. That is a set-once vanity flag with a sharp edge, not something to advertise on a tab. It is already toml- |
| `recognition.voiceHotbarSelect` | client | A real feature with real cost: enabling it injects nine two-word phrases into the grammar, and the recogniser force-fits noise onto the nearest entry, so it directly competes with the spell names it sits beside. Same argument as handsFreeConfirm - worth having |

## The bug, and the reprieve

**`combatOnly` blocked every cast.** `isInCombat()` compared the player's
`getLastHurtByMobTimestamp()` — which vanilla assigns from the entity's own `tickCount` — against
`level().getGameTime()`, the world's age. In any played save the difference is millions of ticks,
so the check was always false and turning the option on silently disabled voice casting entirely.
It was toml-only until this release, which is probably why nobody reported it; it is now a
one-click toggle on the Behaviour tab. **Fixed** — both sides read `tickCount`.

**`voiceVolumeScaling` was struck from the delete list in 0.10.6, not fixed the way
`combatOnly` was.** It was redefined instead: it no longer scales the cast level directly, it
scales how much of `voiceLevelBonus` a spoken cast earns (see `CHANGELOG_0.10.6.md`). The 0.2
gate-close multiplier it was accused of still exists — it still feeds the HUD meter — but the cast
volume no longer passes through it on the way to a spell's level.

## Recommendation

Deleting the five and merging the six takes the config from 57 options to 46, and takes one slider
(`dedupMillis`) off the UI that does nothing at its defaults. Nothing in the delete list is load
bearing: two draw nothing at their defaults, one is a joke that sabotages the diagnostic it rides
on, one is a duplicate latch, one was broken and is now fixed.

The merges are the more interesting half — `dedupMillis` in particular is a slider sitting next to
`echoLockoutMillis` that does nothing at the shipped defaults, because both measure from the same
anchor and the effective suppression is `max(dedup, echoLockout)`.

None of this is done. Deleting config keys changes files players already have, so it is your call.
