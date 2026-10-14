"""Repair JADX's broken byte-counter loops in the decompiled game.

The original code is `for (byte i = 0; i < N; i++) BODY; AFTER`. JADX 1.5.6 turns it into

    byte b3 = 0;                       byte b3 = 0;
    while (true) {                     while (true) {
        byte b4 = b3;                      byte b4 = b3;
        if (b4 >= N) {                     if (b4 >= N) {
            AFTER                              AFTER
        } else {            or                 [missing break]
            BODY                           } else {
            b3 = (byte) (b4 + 1);              BODY
            [missing continue]                 b3 = (byte) (b4 + 1);
        }                                  }
        break;                         }
    }

The left form runs BODY once; the right form never leaves the loop. In the bytecode the
increment jumps back to the test, and only the `>= N` branch exits, so the repair is:
add `continue;` after the increment (left), or `break;` at the end of AFTER (right).

usage: python tools/fix_byte_loops.py <file.java>...
"""
import re
import sys

INC = re.compile(r"^\s*(b\d+) = \(byte\) \((b\d+) \+ 1\);$")
COPY = re.compile(r"^\s*byte (b\d+) = (b\d+);$")
TEST = re.compile(r"^\s*if \((b\d+) (?:>=|<) .+\) \{$")


def indent(s):
    return len(s) - len(s.lstrip(" "))


def close_of(lines, i):
    """Index of the line closing the block opened on line i (same indent, starts with '}')."""
    ind = indent(lines[i])
    for j in range(i + 1, len(lines)):
        if lines[j].strip() and indent(lines[j]) == ind and lines[j].lstrip().startswith("}"):
            return j
    raise ValueError(f"no close for line {i + 1}")


def fix(lines, path):
    changes = []
    i = 0
    while i < len(lines):
        if lines[i].strip() != "while (true) {":
            i += 1
            continue
        end = close_of(lines, i)
        inner = indent(lines[i]) + 4
        copy = COPY.match(lines[i + 1])
        test = TEST.match(lines[i + 2]) if copy else None
        if not (copy and test and test.group(1) == copy.group(1)):
            i += 1
            continue
        counter, alias = copy.group(2), copy.group(1)
        if_open = i + 2
        else_line = close_of(lines, if_open)           # "} else {"
        if lines[else_line].strip() != "} else {":
            # third form: `if (b < N) { BODY; b++ }` and nothing else, so it never exits
            if "<" in lines[if_open] and ">=" not in lines[if_open] and else_line == end - 1 \
                    and INC.match(lines[else_line - 1]):
                ind = " " * indent(lines[else_line])
                lines[else_line:else_line + 1] = [ind + "} else {", ind + "    break;", ind + "}"]
                changes.append(f"{path}:{i + 1}: break when {alias} reaches N (loop had no exit)")
            i += 1
            continue
        else_close = close_of(lines, else_line)
        inc = lines[else_close - 1]
        m = INC.match(inc)
        if not (m and m.group(1) == counter and m.group(2) == alias):
            i += 1
            continue
        after_else = [l.strip() for l in lines[else_close + 1:end] if l.strip()]
        pad = " " * (inner + 4)
        if after_else == ["break;"]:
            # left form: the increment has to loop back
            lines.insert(else_close, pad + "continue;")
            changes.append(f"{path}:{i + 1}: continue after {counter}++")
        elif not after_else:
            # right form: the >= N branch has to leave the loop
            last = lines[else_line - 1].strip()
            if last not in ("break;", "return;", "continue;") and not last.startswith("return "):
                lines.insert(else_line, pad + "break;")
                changes.append(f"{path}:{i + 1}: break at end of the {alias} >= N branch")
        i += 1
    return changes


def main():
    total = 0
    for path in sys.argv[1:]:
        with open(path, encoding="utf-8") as f:
            lines = f.read().split("\n")
        changes = fix(lines, path)
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
        for c in changes:
            print(c)
        total += len(changes)
    print(f"{total} loops repaired")


if __name__ == "__main__":
    main()
