#!/usr/bin/env python3
"""
One command that shows every place players are talking about the mod.

Sources
  GitHub    issues + issue comments (public API, no auth needed for a public repo)
  Modrinth  project + version list (Modrinth has no public comment API, so this reports
            what it can and links straight to the page)
  CurseForge  needs an API key; without one it prints the URL to open

Usage
  python tools/comments.py              # everything since the last run
  python tools/comments.py --all        # ignore the watermark, show everything
  python tools/comments.py --days 14    # only the last 14 days

State lives in tools/.comments-seen.json so a normal run shows only what is NEW.
That is the point: the answer to "is anyone waiting on me" should be a short list
or nothing at all, not a wall you have to re-read every time.
"""
import argparse, json, os, sys, urllib.request, urllib.error
from datetime import datetime, timedelta, timezone

REPO      = "Hoggormino/incantation"
MODRINTH  = "irons-spells-incantation"
CURSE_ID  = os.environ.get("CURSEFORGE_PROJECT_ID", "")
CURSE_KEY = os.environ.get("CURSEFORGE_API_KEY", "")
HERE      = os.path.dirname(os.path.abspath(__file__))
STATE     = os.path.join(HERE, ".comments-seen.json")
UA        = {"User-Agent": "incantation-comment-check/1.0 (+github.com/%s)" % REPO}


def get(url, headers=None):
    h = dict(UA)
    if headers:
        h.update(headers)
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=20) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        print(f"  ! {url} -> HTTP {e.code}")
    except Exception as e:
        print(f"  ! {url} -> {e}")
    return None


def load_state():
    try:
        with open(STATE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_state(s):
    with open(STATE, "w", encoding="utf-8") as f:
        json.dump(s, f, indent=1)


def github(since_iso, seen):
    print("\n=== GitHub =========================================================")
    fresh = 0
    issues = get(f"https://api.github.com/repos/{REPO}/issues?state=all&per_page=50&sort=updated") or []
    for it in issues:
        if "pull_request" in it:
            continue
        key = f"issue:{it['number']}"
        updated = it.get("updated_at", "")
        if not since_iso or updated > since_iso or seen.get(key) != updated:
            state = it.get("state", "?")
            print(f"\n#{it['number']} [{state}] {it.get('title','')}")
            print(f"  by {it.get('user',{}).get('login','?')} · updated {updated}")
            body = (it.get("body") or "").strip().replace("\r", "")
            if body:
                for line in body.split("\n")[:6]:
                    print("  | " + line[:110])
            if it.get("comments", 0):
                cs = get(it["comments_url"]) or []
                for c in cs[-3:]:
                    who = c.get("user", {}).get("login", "?")
                    txt = (c.get("body") or "").strip().replace("\r", "")
                    print(f"  -- {who} ({c.get('created_at','')[:10]}):")
                    for line in txt.split("\n")[:4]:
                        print("     " + line[:105])
            seen[key] = updated
            fresh += 1
    print(f"\n  {fresh} issue(s) new or updated" if fresh else "\n  nothing new")
    return fresh


def modrinth(seen):
    print("\n=== Modrinth =======================================================")
    p = get(f"https://api.modrinth.com/v2/project/{MODRINTH}")
    if not p:
        return 0
    print(f"  {p.get('title')}  ·  {p.get('downloads')} downloads  ·  {p.get('followers')} followers")
    key = "modrinth:downloads"
    prev = seen.get(key)
    if prev is not None and p.get("downloads") != prev:
        print(f"  downloads since last check: +{p.get('downloads', 0) - prev}")
    seen[key] = p.get("downloads")
    print("  Modrinth exposes no public comment API — open:")
    print(f"    https://modrinth.com/mod/{MODRINTH}")
    return 0


def curseforge(seen):
    print("\n=== CurseForge =====================================================")
    if not (CURSE_KEY and CURSE_ID):
        print("  No CURSEFORGE_API_KEY / CURSEFORGE_PROJECT_ID set — open:")
        print("    https://www.curseforge.com/minecraft/mc-mods/incantation")
        print("  (comments are not in CurseForge's public API even with a key;")
        print("   the key only adds download counts and file metadata)")
        return 0
    d = get(f"https://api.curseforge.com/v1/mods/{CURSE_ID}", {"x-api-key": CURSE_KEY})
    if d and "data" in d:
        m = d["data"]
        print(f"  {m.get('name')}  ·  {m.get('downloadCount')} downloads")
        key = "curse:downloads"
        prev = seen.get(key)
        if prev is not None and m.get("downloadCount") != prev:
            print(f"  downloads since last check: +{m.get('downloadCount', 0) - prev}")
        seen[key] = m.get("downloadCount")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true", help="ignore the watermark")
    ap.add_argument("--days", type=int, default=0, help="only items updated in the last N days")
    a = ap.parse_args()

    seen = {} if a.all else load_state()
    since = ""
    if a.days:
        since = (datetime.now(timezone.utc) - timedelta(days=a.days)).isoformat()

    total = 0
    total += github(since, seen) or 0
    modrinth(seen)
    curseforge(seen)
    save_state(seen)

    print("\n====================================================================")
    print("  nothing needs a reply" if total == 0 else f"  {total} thing(s) to look at")
    print("  state:", STATE)


if __name__ == "__main__":
    main()
