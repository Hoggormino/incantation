# Incantation 0.10.4 — a safety fix and a new face

Update recommended for everyone on 0.10.3. One of the fixes protects your files.

## Important: custom model folders are safe now

If you pointed `modelPath` at a Vosk model you installed yourself and the folder layout was
not exactly what the mod expected, 0.10.3 could **delete that folder** while auto-downloading
the default model into its place. If you kept several models in one directory, all of them
were at risk.

The mod now refuses to delete any directory it did not create itself. If your `modelPath`
looks wrong it says so in the log and touches nothing.

That guard was initially too strict and blocked automatic model downloads on a clean install;
it now correctly treats an empty folder as free to use, and if an install fails halfway your
previous model is put back.

## If the mod "does nothing", this is probably why

Windows picks your default recording device, and on a lot of machines that default is a
*virtual* one — iVCam, VB-Cable, NVIDIA Broadcast, OBS, or a webcam that is plugged in but not
actually listening. Those devices open perfectly, report that audio is available, and then hand
back pure silence forever. The mod believed them: mic status said "capturing", nothing was
logged, diagnostics said OK, and no spell ever fired.

Three things changed:

- **New screen: Config → More… → Microphone & Sound.** Pick your microphone from a list instead
  of typing a device name into the toml. "Test all mics" listens to each one for about a second
  and tells you which are live, which are silent, and which will not open. There is a live level
  meter so you can watch your own voice register before you leave the screen. Minecraft's sound
  output device is switchable from the same place.
- **The mod now notices.** A microphone that is open and delivering exact silence for four
  seconds gets called out: the HUD dot turns red and says "mic silent", diagnostics reports FAIL
  instead of OK, and the log names the device and where to change it.
- **The welcome wizard offers the picker** on the mic-check step, which is where you end up
  stuck if your default device is one of the dead ones.

## The UI looks like Minecraft now

Every screen has been redone in the layout Minecraft uses for its own settings screens: no panel at all, the blurred world behind,
white shadowed text, and the header and footer rules the game draws. Every control is now a real
vanilla widget rather than a hand-drawn imitation, so buttons and sliders match the rest of the
game exactly — and follow your resource pack if you use one.

The HUD is quieter too: new installs show plain shadowed text over the world with no chip
behind it, like the vanilla action bar. Existing configs keep whatever you had; set
`background` in the `[colors]` section to taste.

**Removed: theme colours and menu palettes.** The ten accent presets (including the ones unlocked
by cast milestones) and the four surface palettes are gone, and the `themePreset` and `uiPalette`
keys are dropped from your config the first time 0.10.4 loads. Nothing else in your config is
touched.

There is one style now, and it is the game's own. Every screen having to stay legible against
forty combinations of surface and accent was the direct cause of a long tail of unreadable-text
bugs, and a settings screen tinted in a chosen colour reads as a mod skin rather than as part of
Minecraft. The one place a personal colour still makes sense — the in-world HUD — keeps its
highlight.

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
