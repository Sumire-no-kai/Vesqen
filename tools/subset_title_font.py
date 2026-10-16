#!/usr/bin/env python3
"""Cut a Noto Serif CJK SemiBold source down to the characters of Vesqen's page titles.

usage: subset_title_font.py NotoSerif{SC,TC,JP}-SemiBold.otf

Sources: Serif/SubsetOTF/{SC,TC,JP}/NotoSerif{SC,TC,JP}-SemiBold.otf from https://github.com/notofonts/noto-cjk
at 9b0f1436e455d902de067a2501422e5dc71ad16b (Noto Serif CJK 2.003, checked against the hashes in
CUTS), SIL OFL 1.1. The source files stay outside the repository; only the cuts are bundled. Each
source is recognized by its hash and writes its own cut: printable ASCII, for English titles and
library names, plus every character of the string resources listed in title_font_strings.txt in
that cut's languages. Android picks the cut through the font directory's language qualifier.
Needs fontTools (pip install fonttools). After running it, update the cut's hash in
third-party-licenses/catalog.json.
"""
import hashlib
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from fontTools import subset

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
# Source SHA-256 -> (source name, output font directory, string directories whose titles it covers).
CUTS = {
    "517d9736136b3b4e9aea742a7e1acda1922aea91a4425bd8db79281934055bf5":
        ("Noto Serif SC SemiBold 2.003", "font", ("values", "values-zh-rCN")),
    "e59aa64fa67b08efd000372cba5c4902424e50efef962084377c02c80f5ad593":
        ("Noto Serif TC SemiBold 2.003", "font-b+zh+Hant", ("values-b+zh+Hant",)),
    "116d06c2b11ceba33ccb3f8c9eb1c86aba3d5761a1199fd37f74e83365e7a53d":
        ("Noto Serif JP SemiBold 2.003", "font-ja", ("values-ja",)),
}
OUTPUT_NAME = "title_serif_semibold.otf"


def title_keys():
    lines = (ROOT / "tools/title_font_strings.txt").read_text(encoding="utf-8").splitlines()
    return [line.strip() for line in lines if line.strip() and not line.startswith("#")]


def title_text(locales):
    keys = set(title_keys())
    found = {}
    for locale in locales:
        for element in ET.parse(RES / locale / "strings.xml").getroot().iter("string"):
            name = element.get("name")
            if name in keys:
                # Android escapes such as \' are not characters of the title.
                found[(locale, name)] = re.sub(r"\\(.)", r"\1", "".join(element.itertext()))
    missing = [(locale, key) for locale in locales for key in keys if (locale, key) not in found]
    if missing:
        raise SystemExit(f"Title strings not found: {missing}")
    return "".join(found.values())


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    cut = CUTS.get(hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest())
    if cut is None:
        raise SystemExit("Source font is none of the pinned Noto Serif CJK 2.003 files; review it first")
    _, directory, locales = cut
    output = RES / directory / OUTPUT_NAME
    characters = set(map(ord, title_text(locales))) | set(range(0x20, 0x7F))
    options = subset.Options()
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.notdef_outline = True
    options.drop_tables += ["DSIG", "VORG", "vhea", "vmtx", "BASE"]
    font = subset.load_font(sys.argv[1], options)
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=sorted(characters))
    subsetter.subset(font)
    output.parent.mkdir(exist_ok=True)
    subset.save_font(font, str(output), options)
    print(f"{output.relative_to(ROOT)}: {len(characters)} characters, {output.stat().st_size} bytes")


if __name__ == "__main__":
    main()
