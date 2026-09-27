# Android / iOS parity

Both sides are 1206×2622. The iOS frames are the iPhone 17 Pro references already in this repo (dark). The Android frames are the debug demo build on an Android 15 emulator with the display overridden to the same pixel size and 480 dpi, in dark mode, on the same demo workspace.

| Pair | iOS | Android state |
| --- | --- | --- |
| `sessions-list.png` | `mobile-polish/home-glass.png` | Sessions front page |
| `space-filter.png` | `mobile-polish/project-menu.png` | “All” space-filter menu open |
| `tool-activity.png` | `mobile-polish/tool-activity.png` | “Tool group header colors” |
| `multiline-keyboard.png` | `mobile-polish/multiline-keyboard.png` | Composer focused, keyboard up, draft in the field |
| `send-runway.png` | `mobile-polish/send-runway.png` | Keyboard open on that session (not a separate runway animation) |
| `ios-portrait.png` | `appshots/appshots-ios-portrait.png` | Same tool-group session |

Raw Android frames: `android-sessions.png`, `android-space-filter.png`, `android-transcript.png`, `android-keyboard.png`, `android-multiline.png`, `android-settings.png`.

## What matches

- Demo backdrop is `#0D0D0D`, the same near-black as `home-glass` (no large “Sessions” title, no tab bar, no “New session” accessory).
- Header is a glass “All” capsule and a glass capsule with new-session and profile. Profile opens Settings.
- Rows are three lines: `project @ machine` with status on the right, title, then branch and a colored `#PR` chip. Swipe labels are not drawn while the row is at rest.
- Archived sessions sit under an “Archived” divider (below the first screen; the demo front page is taller than the iOS crop).
- Space filter lists All, each project with `@ machine` and offline when the host is down, and “New space…”.
- Transcript header is a round back chevron, the agent mark, the title, and `project @ machine`, with the list starting under an edge fade. Resting composer placeholder is “Message” in a full-width capsule with a circular send button. The tool-group summary and the red Failed row come from the Rust layout.

## Remaining differences

- **Glass.** A `RenderNode` capture of the Compose tree (so capsules could blur the pixels behind them) segfaults the emulator GPU. Capsules are a frosted `#1E1E1E` fill, hairline, and shadow. On this flat backdrop that fill measures about `(29,29,29)`, next to the iOS capsule at about `(30,30,30)`. They do not blur scrolling transcript text.
- **Space-filter order.** Projects follow workspace order (blog, zeron, edge, Zeron iOS). The iOS crop leads with zeron, then edge.
- **Archived placement.** The divider is on the list, under the active rows, so it is not in the first-screen pair.
- **Profile** is a drawn person glyph, not a photo.
- **Composer extras.** The plus button appears once the field is focused. Model and PR chips show on the expanded card; they do not open the iOS menus. The multiline frame’s draft was entered with `adb input text`, so the wording is not the iOS sentence.
- **Transcript type.** Rails, icons, and wrapping are the Rust display list painted with Geist. They are close, not a pixel match to the UIKit text system (line breaks, the exact tool-icon set, the summary chevron).
- **Not recaptured.** `appshots-ios-landscape`, `appshots-ios-queue-actions`, `appshots-ios-queue-gallery`, `appshots-ios-lightbox`, and `appshots-ios-first-capture` need rotation, an in-flight queue, or an attachment. Those states were not reproduced.
