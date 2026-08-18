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
