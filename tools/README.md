# tools/

## comments.py — "is anyone waiting on me?"

    python tools/comments.py            # only what is NEW since the last run
    python tools/comments.py --all      # everything, ignore the watermark
    python tools/comments.py --days 14  # only the last two weeks

Shows GitHub issues and their replies in full, plus Modrinth download/follower
counts and the deltas since you last looked.

It keeps a watermark in `tools/.comments-seen.json`, so a normal run prints a
short list or "nothing needs a reply" rather than a wall you have to re-read.

### Why the two storefronts are links rather than text

Neither CurseForge nor Modrinth exposes player comments through a public API.
Modrinth has no comment endpoint at all. CurseForge's API needs a key and still
only returns project and file metadata — comments are not in it. So the script
reports the numbers it CAN see (downloads, followers, and the change since your
last run, which is the useful part) and links straight to the page for the rest.

Setting `CURSEFORGE_API_KEY` and `CURSEFORGE_PROJECT_ID` adds CurseForge download
counts to the same summary. Get a key from https://console.curseforge.com/.
