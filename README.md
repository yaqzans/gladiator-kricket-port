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
| `gk/GameView.java` | Scales the frame to the screen, turns touches into the phone's key presses |
| `gk/Snapshot.java`, `gk/Host.java` | Exact save and resume of a match; pause on calls / audio focus loss |

### Playing it

The game is redrawn in HD and fills the whole screen; there are no on-screen keypads.

- **HD graphics:** every sprite and background has a 4x version made with Real-ESRGAN
  (`tools/upscale.py`), and every draw call is replayed at the phone's full resolution on the
  GPU (`Graphics.java` records each frame as a `Picture`). Text is drawn in a real font,
  fitted to the original letter widths, so every line and wrap lands where it always did.
- **Full screen:** the game gets a canvas 240 wide and as tall as the phone's screen shape; its
  own code reads the size, so a match shows more of the stadium, edge to edge. Menus keep their
  layout, centred, with the background art covering the screen.

- **In a match, touch anywhere to swing.** The swing happens the instant your finger lands,
  so timing works exactly like pressing 5 on the phone did.
- **Swipe to aim** (an extra, not in the original): swipe down while swinging to hit it
  straight back past the bowler, right or left for square shots, diagonals in between. A plain
  tap keeps the original game's own choice of shot. Timing still decides whether you connect,
  a ball the game rules unplayable stays unplayable, and fielders, catches and the lions work
  exactly as before.
- **Menus:** tap the item you want; swipe for up / down. The phone's soft-key labels (SELECT,
  BACK...) are not drawn: tap a screen to go on, Android back to go back.
- **Reaching the target ends the match** with the game's own win sequence (the original kept
  batting until the balls ran out).
- **Pause:** the small button at the top right, or Android back (the game's own pause).

### Leaving and coming back

- Home, an incoming call (even as a banner), another app's sound, or unplugging headphones
  pauses the match the way a call did on the original phone: back to the menu with *Continue*.
- If Android closes the app, or you choose Exit mid-match, the match is saved exactly as it
  was (score, balls, lives, the ball in flight, the lions). Next launch, the menu offers
  *Continue* (that match) or *New Game*.
- The save is a field-by-field snapshot of the game's objects (`gk/Snapshot.java`); on start
  the match is loaded the normal way, then the saved state is written over it.

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
# optional, for the HD graphics (needs PyTorch; ~15 min on a CPU):
python tools/upscale.py RealESRGAN_x4plus_anime_6B.pth app/src/main/resources app/src/main/resources/hd
./gradlew assembleDebug
```

The menu label images are small serif text the upscaler garbles, so they are redrawn with
`python tools/menu_labels.py Cinzel.ttf <original images> app/src/main/resources/hd` (Cinzel,
SIL Open Font License, github.com/google/fonts ofl/cinzel).

Without the HD images the game still runs, drawn from the original pixels. The Real-ESRGAN
weights are on github.com/xinntao/Real-ESRGAN (release v0.2.2.4).

The APK is in `app/build/outputs/apk/debug/`.
