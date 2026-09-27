# Android / iOS parity

Both sides are 1206×2622. The iOS frames are the iPhone 17 Pro references already in this repo (dark). The Android frames are the debug demo build on an Android 15 emulator with the display overridden to the same pixel size and 480 dpi, in dark mode, on the same demo workspace.

| Pair | iOS | Android state |
| --- | --- | --- |
| `sessions-list.png` | `mobile-polish/home-glass.png` | Sessions front page |
| `space-filter.png` | `mobile-polish/project-menu.png` | “All” space-filter menu open |
| `tool-activity.png` | `mobile-polish/tool-activity.png` | “Tool group header colors”, group expanded |
| `multiline-keyboard.png` | `mobile-polish/multiline-keyboard.png` | Composer focused, Gboard up, wrapping draft |
| `send-runway.png` | `mobile-polish/send-runway.png` | Same session, Gboard up, one-line draft |
| `ios-portrait.png` | `appshots/appshots-ios-portrait.png` | Same expanded tool-group session |

Raw Android frames: `android-sessions.png`, `android-space-filter.png`, `android-transcript.png`, `android-keyboard.png`, `android-multiline.png`, `android-settings.png`, `android-archived.png` (list scrolled to the Archived divider).

## What matches

- Demo backdrop is `#0D0D0D`. Header is a glass “All” capsule and a glass capsule with new-session and profile. No large “Sessions” title and no tab bar.
- Rows are three lines with no leading icon column. Line 1 is `project @ machine` and the status. Line 2 is the title at regular weight, 16.5sp. Line 3 is a small agent mark, the branch icon, the branch name, and a right-aligned `#PR` chip. “Done” is only shown for an unseen completed session.
- The Archived divider is the same treatment as the iOS frame: “Archived” with a chevron, then single-line titles and a relative time (`3d`, `6d`). It is reachable by scrolling. See `android-archived.png`.
- The space menu has the checkmark on the left of “All”, then projects in recent-activity order (zeron, edge, Zeron iOS, blog), a divider, and a folder-plus “New space…”. Session status words are not drawn in the menu. A dim scrim sits behind it.
- The tool group starts collapsed, matching the iOS default for a finished group. Expanded, the header reads the Rust summary, with a down chevron, a rail, and the rows: Search `group_header_color`, red terminal “Run” + red “Failed” + the command, pencil “Edit” `shell/transcript.rs`, then the successful re-run. The user bubble is left-aligned and nearly the full column. Inline code is purple-tinted text on a faint accent wash, with 2pt of side padding.
- The focused composer is one field: full-width text, then a row with plus, model / effort / PR chips, and send. Chips do not open menus. The resting capsule is “Message” and send.
- The system IME is Gboard. The composer and transcript sit on `WindowInsets.ime` (unioned with the navigation bar). Checked at two heights: Gboard’s inset top was y=1614 (1008px tall) and the voice IME’s was y=1736 (886px tall). In both cases the field stayed above the inset. Gboard’s suggestion strip was visible and did not cover the field.

## Glass

A full-tree `RenderNode` + `RenderEffect` blur segfaults this emulator’s GPU (round 2). A software `View.draw` into a bitmap was not kept: the first attempt crashed the activity by writing a sentinel under `/tmp`, which the app cannot access. The path that shipped is a downscaled `PixelCopy` of the window, box-blurred on the CPU (radius 2 at 1/8 resolution), sampled inside the capsule with a 0.72 `#1E1E1E` wash. The success file is `cache/glass-blur-ok`. On this flat backdrop the capsule still measures about `(29,29,29)`, next to the iOS capsule at about `(30,30,30)`.

## Remaining differences

- **Front page length.** The iOS `home-glass` frame shows four active rows and then Archived, with empty backdrop below. The current demo fixture has ten active sessions (pinned, P0, Mobile, and Recent), so Archived starts below the first screen. Row pitch is 74dp, the measured iOS pitch. The divider itself matches; the list is longer.
- **Picker status.** The fixture marks “Model picker catalog sync” as awaiting input, so the row says “Input”. The iOS frame shows “Done”.
- **Tool summary wording.** The shared layout string is “Ran 2 commands · edited 1 file · searched 1 time · 1 failed”, because the transcript has two execs, one edit, and one search. The iOS PNG says “Ran 1 command · edited 1 file · 1 search · 1 failed”. The expanded body also includes the successful second `cargo test` row.
- **User bubble inset.** The bubble’s left edge is the 18pt column margin (about x=54). The iOS frame’s bubble starts nearer x=77.
- **Inline code color.** Android paints accent-tinted text (`#C4B5FD` on a 16% accent wash). The iOS PNG’s bubble and assistant text do not contain purple pixels; that build’s inline code is the gray palette.
- **Menu contents.** The menu lists every project. The iOS PNG’s popover shows All, zeron, and edge before “New space…”. The scrim is a 45% black dim; the iOS PNG’s already-black backdrop does not read as dimmed outside the panel. There is no live backdrop blur behind the menu.
- **Profile** is a drawn person glyph, not a photo.
- **Composer chips** are labels only. They do not open the model, effort, or PR menus.
- **CJK.** No CJK keyboard is installed (Gboard and the voice IME only). Composing text and candidate windows were not exercised. Gboard’s Latin suggestion strip was not clipped. The CJK session title is drawn with Geist, which does not cover those glyphs, so that row’s title does not match the system-font iOS rendering.
- **IME animation.** Inset padding follows `WindowInsets.ime`. Captures were taken with animator duration scale 0, so the move is immediate in the stills. With scale 1, both keyboard heights left the composer on the inset.
- **Not recaptured.** `appshots-ios-landscape`, `appshots-ios-queue-actions`, `appshots-ios-queue-gallery`, `appshots-ios-lightbox`, and `appshots-ios-first-capture` need rotation, an in-flight queue, or an attachment.
