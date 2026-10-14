# Gladiator Kricket for Android

An Android port of *Gladiator Kricket*, a 2011 J2ME (Java phone) cricket game. It plays the
original game by touch, scaled up to fill a modern phone screen with sharp pixels.

**This repo contains no game code, art or sound.** You need your own copy of the original
jar (`gladiatorc_*.jar`, sha256 `e4d62988...3745`). A script decompiles it on your machine,
applies the fixes in `tools/game-fixes.patch`, and the Android build does the rest.

## How it works

The original game is ~5,000 lines of obfuscated Java written against the MIDP 2.0 phone API.
Instead of rewriting it, this port keeps that code and supplies its own small implementation
of the parts of `javax.microedition.*` the game actually calls:

| Piece | What it does |
|---|---|
| `javax/microedition/lcdui/Graphics.java` | MIDP drawing on an Android `Canvas`: anchors, `setClip`, all 8 `drawRegion` transforms |
| `javax/microedition/lcdui/Canvas.java`, `gk/Host.java` | The game's own loop calls `serviceRepaints()`; each call renders one 240x320 frame |
| `javax/microedition/media/Player.java` | MIDI music and effects through Android `MediaPlayer` |
| `javax/microedition/rms/RecordStore.java` | Save data (unlocked matches, scores) in app storage |
| `gk/GameView.java` | Scales the frame to the screen, draws the touch buttons, turns touches into key presses |

### Controls

- **Tap the game** to swing the bat (or to select / continue in menus).
- **Swipe** on the game for up / down / left / right in menus.
- Buttons under the game: **OK** and **BACK** (the phone's soft keys), a **D-pad**, and **HIT**.
- Android **back** pauses a match.

### Decompiler fixes

JADX 1.5.6 decompiles the game without complaint, but the result is subtly wrong in places.
Every fix was checked against the original bytecode with `javap -c`:

- 24 `for` loops with `byte` counters came out as loops that run once or never stop
  (`tools/fix_byte_loops.py`).
- Two more missing `continue`s: the stadium map loader and the text wrapper.
- One-letter class names (`a`..`f`) hidden by fields with the same names
  (`tools/qualify_names.py`).
- Three catch blocks where JADX merged two variables.

`tools/game-fixes.patch` contains all of them; the two scripts are kept to show how they were made.

## Build

Needs JDK 17, the Android SDK (platform 35, build-tools 35), Python 3 and
[JADX 1.5.6](https://github.com/skylot/jadx/releases/tag/v1.5.6).

```bash
python tools/prepare.py path/to/gladiatorc.jar path/to/jadx/bin/jadx
./gradlew assembleDebug
```

The APK is in `app/build/outputs/apk/debug/`.
