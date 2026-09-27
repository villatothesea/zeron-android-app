# Android / iOS parity

Android shots are from the debug build in demo mode on an Android 15 x86_64 emulator (1080×2400). iOS shots are the existing iPhone 17 Pro references in this repo. They are not the same device, the same moment in the demo stream, or the same crop, so the pairs are structural rather than pixel diffs.

| Pair | iOS reference | Android |
| --- | --- | --- |
| Sessions list | `mobile-polish/home-glass.png` | demo front page: pinned, sections, recent, New session accessory, tab bar |
| Transcript | `mobile-polish/tool-activity.png` | “Streaming veil on transcript rows”: tool groups, code block, composer |
| New session | `mobile-polish/project-menu.png` | new-session sheet with the prompt and project list |

## What matches

- Light palette (cool gray page, violet status, Claude mark, project letter tiles).
- Session rows: title, project tile, branch, PR number, Working / Input / Failed / Done / time.
- Foldable Pinned, user sections, and Recent, with counts.
- “New session” capsule above the tab bar with a live “1 working · 1 needs you” summary.
- Transcript painted from the Rust `LayoutFrame`: tool rows, a fenced code block with a copy control, user text, and the glass composer capsule (“Message Claude Code”).
- Settings: account (Demo), devices, appearance, wallpaper, archived sessions, sign out.
- Search field over the same rows.
- Offline demo is the debug default.

## Remaining differences

- **Glass.** Android uses a translucent fill, hairline, and shadow. It does not sample the wallpaper or transcript the way iOS Liquid Glass does, so capsules look flatter.
- **Tab bar.** Icons and labels are a Compose row. There is no iOS 26 tab-bar minimize or search morph. Search is a magnifying-glass stand-in, not an SF Symbol.
- **New session.** The hero and composer match the canvas. Project, harness, and model choices are a list under the composer rather than the iOS chip menus, and the sheet is a full-screen cover rather than a page sheet with a grabber.
- **Composer.** Send / stop / long-press queue-steer-interrupt, attachments, and `@` mentions are implemented. Context chips are labels; they do not open the iOS model, effort, and branch menus.
- **Transcript chrome.** The top bar is a custom Back / title / overflow row, not a UIKit navigation bar. Streaming veil and tool rails are drawn, but motion and spacing will not match the simulator frame for frame because the demo stream is live.
- **Wallpaper.** The Rust shader path runs when a photo is chosen. The demo has no default wallpaper, so these shots are the flat page color. iOS `home-glass` shows a wallpaper behind the list.
