# Gladiator Kricket: what was tested

Tested on an Android 14 emulator (Pixel 6 profile, 1080x2400, punch-hole cutout), debug and
release builds. Not testable here: a real phone's touch feel, speaker output and vibration.

## Gameplay is the original

| Case | Result |
|---|---|
| Rules, timing, scoring, lions, catches, fielding unchanged | Only decompiler errors were fixed, each checked against the original bytecode (`javap -c`); see README |
| Frame rate | 95 ms per frame average, the original's own timer |
| Bowling and shot direction are automatic in the original | Confirmed in the bytecode (manual bowling flag is set once and never cleared) |
| Win a match (100+ runs), next match unlocks, save survives a force-close | Pass |
| Lose a match (balls / lives run out), scoreboard, back to menu | Pass |
| Intro and help text wrap at whole words | Pass (was broken by a decompiler error, fixed) |

## Touch

| Case | Result |
|---|---|
| Touch anywhere in a match swings at that instant | Pass |
| Very short tap (shorter than one 95 ms frame) still swings | Pass (each press held one frame) |
| Swipe-to-aim (extra): left / right / down / diagonals, applied after the swing | Pass: left 154-206 deg, right 331-357, down 70-107, down-right 30-74 |
| Aim never makes an unplayable (wide) ball hittable | By design: aim is skipped when the game's shot type is 0; reviewed |
| A swipe after contact or between balls does not aim the next ball | Fixed after review |
| Tap a menu item directly (New Game, Load Game, Match 01...) | Pass |
| Two quick menu taps | By design after review: second tap ignored until the first finishes; not separately run |
| Locked matches cannot be tapped | By design (tap only reaches unlocked rows); not separately run |
| Swipes in menus and text screens = arrow keys | Pass |
| Bottom corners = soft keys (labels drawn there) | Pass |
| Pause button (top right) = the game's own pause | Pass |
| Second finger while batting | Ignored (by design) |

## Leaving and coming back

| Case | Result |
|---|---|
| Home mid-match, return | Menu with Continue (what a call did on the original phone); Continue resumes |
| Incoming call (banner) mid-match | Pauses when it starts ringing; after the call, menu with Continue; music comes back |
| Headphones unplugged | Pauses (code path; no headphones in the emulator) |
| Back button in a match | The game's pause |
| App killed in the background, reopened | Exact resume: balls, runs, lives, lions, mid-delivery state identical (counters logged at save and restore) |
| Exit from the menu with a match in progress, reopen | Continue still there |
| New Game after a restore | Starts a fresh match |
| Save written while the next match is loading | Kept, not deleted |
| Saved file unreadable / from an older version | Ignored, normal start |

## Screen

| Case | Result |
|---|---|
| Fills the phone width, proportions kept, even pixel sizes | Pass (sharp-bilinear: whole-number nearest scale, then smooth) |
| Notch / punch hole, hidden system bars, gesture navigation | Pass (safe-area insets) |
| Portrait locked | Pass |

## Release build

| Case | Result |
|---|---|
| Signed release APK installs and plays | Pass |
| Test bot not in the release APK | Checked: `gk.Autoplay` is only in the debug source set |
