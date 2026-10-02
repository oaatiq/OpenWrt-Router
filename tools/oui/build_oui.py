#!/usr/bin/env python3
"""Build the compact OUI vendor table shipped with the Android app.

Input:  index.json from the `oui-data` npm package (IEEE MA-L registry,
        BSD-2-Clause, https://github.com/silverwind/oui-data)
Output: android/core/data/src/main/assets/oui.tsv
        one "AABBCC<TAB>Vendor" line per prefix, vendor names shortened
        (legal suffixes removed, ALL CAPS names title-cased).

Usage:  npm pack oui-data && tar xzf oui-data-*.tgz
        python3 tools/oui/build_oui.py package/index.json
"""
import json
import re
import sys
from pathlib import Path

SUFFIXES = re.compile(
    r'[,.\s]+(inc|incorporated|corp|corporation|co|company|ltd|limited|llc|l\.l\.c|gmbh|ag|sa|s\.a|'
    r'srl|s\.r\.l|spa|s\.p\.a|bv|b\.v|nv|n\.v|oy|ab|as|a/s|kg|plc|pte|pty|sas|s\.a\.s|sarl|kk|k\.k|'
    r'co\.,?\s*ltd|technology|technologies|electronics|international)\.?\s*$', re.I)
KEEP_UPPER = {'IBM', 'HP', 'LG', 'TP-LINK', 'ZTE', 'AVM', 'NEC', 'BMW', 'ASUSTEK', 'D-LINK', 'TCL', 'HTC', 'AMD', 'QNAP', 'GE'}


def clean(name: str) -> str:
    name = name.split('\n', 1)[0].strip().strip('"')
    name = re.sub(r'\s+', ' ', name)
    for _ in range(3):
        new = SUFFIXES.sub('', name).strip(' ,.-')
        if new == name or not new:
            break
        name = new
    if name.isupper() and len(name) > 4 and name not in KEEP_UPPER:
        name = ' '.join(w if w in KEEP_UPPER or len(w) <= 3 else w.capitalize() for w in name.split(' '))
    return name[:48]


def main() -> int:
    src = Path(sys.argv[1] if len(sys.argv) > 1 else 'package/index.json')
    out = Path(__file__).resolve().parents[2] / 'android/core/data/src/main/assets/oui.tsv'
    data = json.loads(src.read_text())
    lines = [f'{k.upper()}\t{clean(v)}' for k, v in sorted(data.items()) if len(k) == 6 and v.strip()]
    out.write_text('\n'.join(lines) + '\n')
    print(f'{len(lines)} prefixes -> {out} ({out.stat().st_size // 1024} KiB)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
