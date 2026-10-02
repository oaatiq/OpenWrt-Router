#!/usr/bin/env python3
"""Static checks for WrtPilot ucode sources.

ucode differs from JavaScript in ways that only show up at runtime:

1. Function declarations are not hoisted: calling a top-level function
   (or using a top-level let/const) above its declaration compiles to a
   global lookup and throws "access to undeclared variable" when it runs.
2. ucode releases before 2026-02 (OpenWrt 23.05 / 24.10) need a semicolon
   after `export function name() { ... }`.
3. Shorthand object properties ({ a, b }) are not supported.

Usage: lint_ucode.py [--fix] [paths...]   (default: ../files)
"""
import re
import sys
from pathlib import Path

DECL = re.compile(r'^(?:export\s+)?(?:function\s+([A-Za-z_]\w*)\s*\(|(?:let|const)\s+([A-Za-z_]\w*)\b)')
IDENT = re.compile(r'(?<![\w.$])([A-Za-z_]\w*)\b')
SHORTHAND = re.compile(r'return\s*\{\s*[A-Za-z_]\w*\s*(,\s*[A-Za-z_]\w*\s*)*\}\s*;')


def strip_strings_and_comments(line: str) -> str:
    out = []
    i, n = 0, len(line)
    quote = None
    while i < n:
        c = line[i]
        if quote:
            if c == '\\':
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
            continue
        if c in '\'"`':
            quote = c
            out.append(' ')
            i += 1
            continue
        if line.startswith('//', i):
            break
        out.append(c)
        i += 1
    return ''.join(out)


def is_ucode(p: Path) -> bool:
    if p.suffix == '.uc':
        return True
    try:
        return p.read_bytes()[:24].startswith(b'#!/usr/bin/ucode')
    except OSError:
        return False


def check_file(p: Path, fix: bool) -> list:
    problems = []
    lines = p.read_text().split('\n')

    # 1. top-level declarations and their line numbers
    decls = {}
    for i, line in enumerate(lines):
        m = DECL.match(line)
        if m:
            name = m.group(1) or m.group(2)
            decls.setdefault(name, i)

    in_block_comment = False
    for i, raw in enumerate(lines):
        line = raw
        if in_block_comment:
            if '*/' in line:
                line = line.split('*/', 1)[1]
                in_block_comment = False
            else:
                continue
        if '/*' in line and '*/' not in line.split('/*', 1)[1]:
            line = line.split('/*', 1)[0]
            in_block_comment = True
        if re.match(r'^\s*(import|export\s*\{)', line):
            continue
        code = strip_strings_and_comments(line)
        # drop regex literals roughly
        code = re.sub(r'/(?:\\.|[^/\n])+/[gis]*', ' ', code)
        for m in IDENT.finditer(code):
            name = m.group(1)
            if name in decls and i < decls[name]:
                # property keys "name:" are fine
                rest = code[m.end():].lstrip()
                if rest.startswith(':') and not rest.startswith('::'):
                    continue
                problems.append(f'{p}:{i + 1}: "{name}" used before its declaration on line {decls[name] + 1}')

        if SHORTHAND.search(code):
            problems.append(f'{p}:{i + 1}: shorthand object properties are not supported by ucode')

    # 2. export function semicolons
    in_export = False
    for i, line in enumerate(lines):
        if re.match(r'^export function ', line):
            in_export = True
        elif in_export and line.startswith('}'):
            if line.rstrip() != '};':
                if fix:
                    lines[i] = '};'
                else:
                    problems.append(f'{p}:{i + 1}: export function must end with "}};" for ucode < 2026')
            in_export = False
    if fix:
        p.write_text('\n'.join(lines))
    return problems


def main() -> int:
    fix = '--fix' in sys.argv
    roots = [a for a in sys.argv[1:] if not a.startswith('--')]
    if not roots:
        roots = [str(Path(__file__).resolve().parent.parent / 'files')]
    problems = []
    for root in roots:
        rp = Path(root)
        files = [rp] if rp.is_file() else sorted(x for x in rp.rglob('*') if x.is_file())
        for f in files:
            if is_ucode(f):
                problems += check_file(f, fix)
    for pr in problems:
        print(pr)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
