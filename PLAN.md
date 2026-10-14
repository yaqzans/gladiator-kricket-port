# GladiatorKricket: Android port plan

## The problem

`original/gladiatorc_4tmizzys.jar` is a 2011 J2ME game (MIDP-2.0, CLDC-1.0). Modern phones
can only run it inside an emulator such as J2ME Loader, with a fake keypad. The goal is a
real Android app (APK) that plays the same game with touch controls.

What the decompile showed (details and line refs in `kb/code-map.md`):
- The game is 5,030 lines of Java in 7 classes; `e.java` (3,810 lines) is the whole game
  canvas. JADX decompiled it with no broken methods, only 3 confused catch blocks.
- The phone API surface is small: Canvas, Graphics (8 drawing calls), Image, a MIDI
  Player, one RecordStore. Plus a score-upload / name-entry path (Form, TextField,
  Alert, HttpConnection, platformRequest) that is only reachable from UPLOAD and `#`.
- Screen is hardcoded 240x320. Logic runs inside `paint()`, about 10.5 fps (95 ms
  target, adaptive sleep), and movement is per frame, so the frame rate must stay.
- Input is raw key codes into a bitmask (`keyPressed`/`keyReleased`, e.java:3352).
  Only edges count. In gameplay the ONLY input is FIRE (-5) to swing; direction is
  automatic. Menus use up/down/FIRE; some screens advance only on the left soft key
  (-6); the right soft key (-7) pauses.

## Approach: shim port

Keep the decompiled game code almost untouched. Write our own small implementations of
the `javax.microedition.*` classes it calls, backed by Android. This is the same idea
J2ME Loader uses, just cut down to exactly what this one game needs.

Why not a rewrite: the gameplay already works and is only ~5k lines. A shim is maybe
1,000 to 1,500 lines of new code, and the game plays exactly like the original.

### What gets written

| File(s) | Why |
|---|---|
| `app/build.gradle`, `settings.gradle`, `AndroidManifest.xml` | Android project, minSdk 21, targetSdk 35, portrait, fullscreen |
| `app/src/main/java/defpackage/*.java` | The decompiled game, copied in. Edits: 3 catch-block fixes, `gnd` loading, drop name Form and upload |
| `javax/microedition/lcdui/Canvas, Graphics, Image, Display, Displayable` | Rendering shim |
| `javax/microedition/media/Manager, Player` | MIDI via Android `MediaPlayer` (plays .mid natively) |
| `javax/microedition/rms/RecordStore` | Saves to a private file, same record ids |
| `javax/microedition/midlet/MIDlet` | Lifecycle, `notifyDestroyed()` finishes the activity |
| `gk/GameActivity.java`, `gk/GameView.java` | Hosts the game, scaling, touch |
| `app/src/main/assets/` | The original PNGs, `.mid` files and `gnd` |

Classes the game references but we will not use (Form, TextField, Alert, Command,
Connector, HttpConnection) get removed from the call sites instead of being shimmed.

### Rendering

- The game draws into a 240x320 offscreen `Bitmap` exactly as on the phone.
- `GameView` scales that bitmap to fit the screen width, nearest-neighbour (no blur),
  aligned to the top. Phones are much taller than 3:4, so this leaves a band of
  free space below the game for controls.
- Graphics details to get right (all found in the code map): `setClip` replaces the
  clip instead of intersecting, `translate` is cumulative, `drawRect` is 1 px wider
  and taller, anchors 0 / 20 / 36, and `drawRegion` with all 8 transforms (the ball
  spin uses them).

### Touch controls

1. **Tap the game area = FIRE.** That is the bat swing in gameplay and "select" in
   menus. One tap per ball, same as pressing 5 on the old phone.
2. **Control strip in the free space under the game:** left soft key, up, down,
   left, right, FIRE, right soft key. These are big buttons for menus and anything
   the tap doesn't cover.
3. **Android back button = right soft key** (pause).
4. **Screens that only advance on the left soft key** (intro, objective, settings):
   tapping the game area sends the left soft key there instead of FIRE, so you never
   need the strip just to get past a text screen. This needs one small hook that
   reads the screen state (`f24a`).
5. **Tap timing fix:** the game only sees a key that is still down at the start of a
   frame (every 95 ms). A very quick tap could be missed, so the shim holds every
   press for at least one full frame before sending the release.

## Steps

1. **Finish setup.** Install SDK packages (platform-tools, android-35, build-tools 35)
   into `E:\AndroidDev\Sdk` (this needs the Android SDK license accepted). Download
   Gradle to `E:\AndroidDev`, with `GRADLE_USER_HOME` on E: too, so C: and D: stay free.
2. **Check the map bug.** `kb/code-map.md` flags a possible bug in the `gnd` loader
   (e.java:3287). Check it with `javap -c` against the original bytecode before
   touching it.
3. **Project skeleton plus copying in the code**, with the 3 catch fixes, the `gnd`
   load and the cut-out Form/upload calls. First goal: it compiles.
4. **Shim, drawing first:** Canvas, Graphics, Image, Display, MIDlet, GameView. Goal:
   splash and menu show correctly.
5. **Touch input** as described above.
6. **Sound and saving:** Player, Manager, RecordStore.
7. **Test on your phone** against the criteria below, then fix what turns up.

## How we know it's done

1. `gradlew assembleDebug` builds an APK with no errors.
2. It installs on your phone and goes from splash to the main menu with nothing in the
   logcat crash log.
3. The menu screen looks the same as the jar running in J2ME Loader on the same phone
   (side-by-side screenshots), with sharp pixels and the right proportions.
4. A whole match can be played by touch only: menu, start the match, hit balls by
   tapping, see runs, pause with back, resume.
5. Frame time logged at 95 ms plus or minus 10 ms, so the game speed matches the
   original.
6. Menu music and hit sounds play.
7. Progress (unlocked match) is still there after force-closing the app.

## Things to know

- **Assets:** the art and sound are Disney-licensed and came from a free download
  site. Fine for your own phone; don't publish the APK or push the assets anywhere
  public. The git repo ignores `original/`, `assets_extracted/` and `decompiled/`,
  and the copied assets will be ignored too.
- **rea:** registered with Claude Code (`~/.claude.json`, backup at
  `~/.claude.json.rea.backup`). It turned out rea only takes APKs, not plain jars, so
  the decompile used JADX 1.5.6 (the engine rea uses) directly. rea becomes useful
  again later, to inspect our own built APK.
- **Disk:** SDK, Gradle and JADX all live in `E:\AndroidDev`, so the port can't build
  while the external drive is unplugged.
- **Rough cost:** steps 1 to 3 are cheap. Steps 4 to 7 are probably 0.5M to 1.5M
  tokens over a few sessions, mostly cached.
