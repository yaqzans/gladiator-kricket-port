"""Fix javac "cannot be dereferenced" errors in the decompiled game.

The obfuscated classes are named a..f, and fields/locals with the same one-letter names
hide them, so `e.f25a` means "field e" instead of "class e". On each line javac flags,
rewrite bare `x.` (x in a..f, not after a dot or identifier) to `defpackage.x.`.

usage: python tools/qualify_names.py <build.log> <src dir>
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

ERR = re.compile(r"^(.*[\\/]defpackage[\\/](\w+)\.java):(\d+): error: "
                 r"(\w+ cannot be dereferenced|cannot find symbol|non-static variable \w+ cannot be referenced)")
BARE = re.compile(r"(?<![\w.])([a-f])\.(?=[A-Za-z_])")

log, src = Path(sys.argv[1]), Path(sys.argv[2])
lines_by_file = defaultdict(set)
for line in log.read_text(errors="replace").splitlines():
    m = ERR.match(line)
    if m:
        lines_by_file[m.group(2)].add(int(m.group(3)))

for cls, nums in sorted(lines_by_file.items()):
    p = src / f"{cls}.java"
    text = p.read_text(encoding="utf-8").split("\n")
    changed = 0
    for n in sorted(nums):
        new = BARE.sub(r"defpackage.\1.", text[n - 1])
        if new != text[n - 1]:
            text[n - 1] = new
            changed += 1
    p.write_text("\n".join(text), encoding="utf-8")
    print(f"{cls}.java: {changed} of {len(nums)} flagged lines rewritten")
