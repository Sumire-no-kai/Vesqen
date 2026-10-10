#!/usr/bin/env python3
"""Cut Noto Serif SC SemiBold down to the characters of Vesqen's page titles.

usage: subset_title_font.py NotoSerifSC-SemiBold.otf

Source: Serif/SubsetOTF/SC/NotoSerifSC-SemiBold.otf from https://github.com/notofonts/noto-cjk at
9b0f1436e455d902de067a2501422e5dc71ad16b (Noto Serif CJK 2.003, checked against SOURCE_SHA256),
SIL OFL 1.1. The source file
stays outside the repository; only the subset is bundled. Keeps printable ASCII, for English titles
and library names, plus every character of the string resources listed in title_font_strings.txt.
Needs fontTools (pip install fonttools). After running it, update the subset's hash in
third-party-licenses/catalog.json.
"""
import hashlib
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from fontTools import subset

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/main/res/font/title_serif_semibold.otf"
SOURCE_SHA256 = "517d9736136b3b4e9aea742a7e1acda1922aea91a4425bd8db79281934055bf5"
LOCALES = ("values", "values-zh-rCN")


def title_keys():
    lines = (ROOT / "tools/title_font_strings.txt").read_text(encoding="utf-8").splitlines()
    return [line.strip() for line in lines if line.strip() and not line.startswith("#")]


def title_text():
    keys = set(title_keys())
    found = {}
    for locale in LOCALES:
        for element in ET.parse(ROOT / f"app/src/main/res/{locale}/strings.xml").getroot().iter("string"):
            name = element.get("name")
            if name in keys:
                # Android escapes such as \' are not characters of the title.
                found[(locale, name)] = re.sub(r"\\(.)", r"\1", "".join(element.itertext()))
    missing = [(locale, key) for locale in LOCALES for key in keys if (locale, key) not in found]
    if missing:
        raise SystemExit(f"Title strings not found: {missing}")
    return "".join(found.values())


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    if hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() != SOURCE_SHA256:
        raise SystemExit("Source font differs from the pinned Noto Serif SC SemiBold 2.003; review it first")
    characters = set(map(ord, title_text())) | set(range(0x20, 0x7F))
    options = subset.Options()
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.notdef_outline = True
    options.drop_tables += ["DSIG", "VORG", "vhea", "vmtx", "BASE"]
    font = subset.load_font(sys.argv[1], options)
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=sorted(characters))
    subsetter.subset(font)
    subset.save_font(font, str(OUTPUT), options)
    print(f"{OUTPUT.relative_to(ROOT)}: {len(characters)} characters, {OUTPUT.stat().st_size} bytes")


if __name__ == "__main__":
    main()
