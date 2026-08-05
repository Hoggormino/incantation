# Gallery card sources

`02-features.html` and `03-owned.html` are the sources for the two text cards in the
storefront gallery. The rest of `gallery/` is in-game screenshots, which have no source.

These two shipped a claim that was false — that the Vosk grammar narrows to your equipped
spells. It doesn't; the grammar is broad and the equipped check runs at dispatch (see
`SpellIndex.getPhrases`). The claim was live as *pictures* on CurseForge long after the
text around them was corrected, which is why the sources live here now instead of only
the flattened PNGs.

Re-render after editing:

```bash
chrome --headless --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
  --window-size=1920,1080 --screenshot=../02-features.png 02-features.html
```

Cards are 1920x1080. The in-game screenshots are 854x480, so they will always look
softer next to these — that's expected, not a rendering bug.
