#!/usr/bin/env python3
"""
Seed a phrasebook from Iron's Spells' own lang file.

Translating ~300 spell phrases by hand is the thing that stops a language getting supported.
Most of it does not need a human: Iron's Spells already ships translations for its own ~229
spells in a dozen languages, so those entries can be filled in from the game's own files and a
contributor is left with only the ADDON spells, which ship no translations in any language.

    python tools/seed_phrasebook.py <irons_spellbooks jar> <lang, e.g. ru_ru> <existing phrasebook> <out>

The existing phrasebook supplies the spell SET (including addon spells) and the English defaults;
this only fills the `override` column, and only where it is currently empty.

Phrases are lowercased and stripped of punctuation, matching SpellIndex.normalize() - Vosk is
matching spoken words, so "Контр-Заклинание" has to become "контр заклинание".
"""
import json, re, sys, zipfile

def normalize(s):
    s = s.lower().replace("'", "").replace("\u2019", "")
    s = re.sub(r"[-\u2013\u2014_/]", " ", s)
    s = re.sub(r"[^\w\s]", "", s, flags=re.UNICODE)
    return re.sub(r"\s+", " ", s).strip()

def main(jar, lang, book_in, book_out):
    with zipfile.ZipFile(jar) as z:
        names = json.loads(z.read(f"assets/irons_spellbooks/lang/{lang}.json").decode("utf-8"))
        en    = json.loads(z.read("assets/irons_spellbooks/lang/en_us.json").decode("utf-8"))

    book = json.load(open(book_in, encoding="utf-8"))
    spells = book.get("spells", {})

    filled = skipped = untranslated = addon = 0
    for spell_id, entry in spells.items():
        ns, _, path = spell_id.partition(":")
        if ns != "irons_spellbooks":
            addon += 1
            continue
        if entry.get("override", "").strip():
            skipped += 1
            continue
        key = f"spell.{ns}.{path}"
        val = names.get(key, "").strip()
        # Identical to English means the translator has not reached it - leave it for the human
        # rather than filling the column with an English word and calling it done.
        if not val or val == en.get(key, "").strip():
            untranslated += 1
            continue
        entry["override"] = normalize(val)
        filled += 1

    book["_seeded"] = (f"irons_spellbooks entries auto-filled from the mod's own {lang}.json; "
                       f"addon spells left blank for a human")
    json.dump(book, open(book_out, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(f"filled {filled} from {lang}.json")
    print(f"  already had an override : {skipped}")
    print(f"  in {lang}.json but untranslated: {untranslated}")
    print(f"  addon spells, need a human    : {addon}")

if __name__ == "__main__":
    if len(sys.argv) != 5:
        print(__doc__); sys.exit(2)
    main(*sys.argv[1:])
