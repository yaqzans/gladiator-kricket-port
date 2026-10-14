"""Bring your own jar: turn your copy of the original game into buildable Android sources.

This repo has no game code, art or sound in it. This script takes the original
GladiatorKricket J2ME jar from your own disk and:
  1. decompiles it with JADX (must be version 1.5.6, the patch is made against its output)
  2. applies tools/game-fixes.patch (decompile errors fixed, see PORTING.md)
  3. copies the images, sounds and map file next to the code as Java resources

Then build with: gradlew assembleDebug

usage: python tools/prepare.py <path to gladiatorc jar> <path to jadx launcher>
  e.g. python tools/prepare.py C:/Downloads/gladiatorc.jar E:/AndroidDev/jadx/bin/jadx.bat
"""
import hashlib
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GAME_SRC = ROOT / "app/src/main/java/defpackage"
RESOURCES = ROOT / "app/src/main/resources"
ICON = ROOT / "app/src/main/res/drawable/icon.png"
PATCH = ROOT / "tools/game-fixes.patch"
# sha256 of the jar the patch was made from; a different build of the game may not patch cleanly
KNOWN_JAR = "e4d62988fef5713c1744b7b806698083ea47863b6fef570ad863aba6b14c3745"


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    jar = Path(sys.argv[1]).resolve()
    jadx = sys.argv[2]

    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    print(f"jar sha256 {digest}")
    if digest != KNOWN_JAR:
        print("warning: not the jar the patch was made from, it may not apply")

    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "jadx"
        subprocess.run([jadx, "-d", str(out), "--no-res", "--show-bad-code", str(jar)],
                       check=True, stdout=subprocess.DEVNULL)
        decompiled = out / "sources/defpackage"
        if GAME_SRC.exists():
            shutil.rmtree(GAME_SRC)
        shutil.copytree(decompiled, GAME_SRC)

    # git apply works outside a repo too; -p2 strips the a/orig/ and b/fixed/ prefixes
    subprocess.run(["git", "apply", "--unidiff-zero", "-p2", f"--directory={GAME_SRC.relative_to(ROOT).as_posix()}",
                    str(PATCH)], check=True, cwd=ROOT)
    print(f"game code -> {GAME_SRC.relative_to(ROOT)}")

    if RESOURCES.exists():
        shutil.rmtree(RESOURCES)
    RESOURCES.mkdir(parents=True)
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.endswith("/") or name.endswith(".class") or name.startswith("META-INF/") \
                    or name.endswith(".txt"):
                continue
            target = RESOURCES / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(z.read(name))
    ICON.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(RESOURCES / "icon.png", ICON)
    print(f"assets -> {RESOURCES.relative_to(ROOT)}")
    print("done, now run: gradlew assembleDebug")


if __name__ == "__main__":
    main()
