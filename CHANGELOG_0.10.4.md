# Incantation 0.10.4 — a safety fix and a new face

Update recommended for everyone on 0.10.3. One of the fixes protects your files.

## Important: custom model folders are safe now

If you pointed `modelPath` at a Vosk model you installed yourself and the folder layout was
not exactly what the mod expected, 0.10.3 could **delete that folder** while auto-downloading
the default model into its place. If you kept several models in one directory, all of them
were at risk.

The mod now refuses to delete any directory it did not create itself. If your `modelPath`
looks wrong it says so in the log and touches nothing.

## The UI looks like Minecraft now

Every screen has been redone in the style of the game's own container screens — the
understated look mods like Simple Voice Chat use. Light stone panels, vanilla buttons and
sliders, tabs that press in like the creative inventory, dark unshadowed text, and the
blurred-world backdrop behind menus. The neon-purple panels are gone.

The HUD is quieter too: new installs show plain shadowed text over the world with no chip
behind it, like the vanilla action bar. Existing configs keep whatever you had; set
`background` in the `[colors]` section to taste.

Your unlocked theme colours still exist and are still earned the same way — they are just
used sparingly now instead of everywhere.

## Also fixed

- Deleting an alias now sticks on NeoForge (it used to come back after a restart).
- If your stats file cannot be read, the mod now proves it has a backup copy before it will
  ever write over it — and refuses if it cannot.
- The mic-test screens (wizard, Test Arena, Live Monitor) no longer fight over the
  microphone; auto-calibrate finishing can no longer cut off a mic test you still had open.
- A calibration you abandoned after a second no longer overwrites your tuned noise gate.
- Closing a mic-test screen mid-sentence can no longer fire a spell later from audio it had
  buffered.
- The config screen no longer overlaps its own buttons in small windows.
