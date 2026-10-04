# Android / iOS parity

> The `android-*.png` raw frames in this folder predate the current UI (round4-era captures). For current screenshots see [docs/screenshots/app](../app).

Both sides are 1206×2622 (3×). The iOS frames are the iPhone references already in this repo (dark). The Android frames are the debug demo build on an Android 15 (API 35, google_apis x86_64) emulator with the display at 1206×2622 and 480 dpi, in dark mode, on the demo workspace. Round 4 frames came from a software-rendered (TCG, no KVM) emulator.

| Pair | iOS | Android state |
| --- | --- | --- |
| `tool-activity.png` | `mobile-polish/tool-activity.png` | “Tool group header colors”, group expanded (two-line rows) |
| `cjk-tool-rows.png` | `mobile-polish/tool-activity.png` | Chinese demo session `chat-zh`, group expanded: CJK arguments in Geist Mono at two cells |
| `cjk-transcript.png` | `mobile-polish/tool-activity.png` | `chat-zh` collapsed: CJK prose via Noto Sans CJK, gray inline code, aligned CJK `text` table and Rust block |
| `ios-portrait.png` | `appshots/appshots-ios-portrait.png` | Same expanded tool-group session |
| `sessions-list.png` | `mobile-polish/home-glass.png` | Sessions front page (with the 中文 and 日本語 rows) |
| `space-filter.png` | `mobile-polish/project-menu.png` | “All” space-filter menu open |
| `chinese-ime-composing.png` | `mobile-polish/multiline-keyboard.png` | fcitx5-android Pinyin, one-line draft, composing “hang” with candidates |
| `chinese-ime-multiline.png` | `mobile-polish/multiline-keyboard.png` | fcitx5 Pinyin, two-line draft, composing “zhe dang” |
| `chip-menu.png` | `mobile-polish/send-runway.png` | Reasoning-effort chip menu open (the repo has no iOS chip-menu frame; the pair shows the chip row) |
| `landscape.png` | `appshots/appshots-ios-landscape.png` (rotated upright) | Same session in landscape, no queue |
| `multiline-keyboard.png`, `send-runway.png` | `mobile-polish/…` | Round 3 (Gboard); not recaptured |

Raw Android frames: `android-transcript.png`, `android-sessions.png`, `android-space-filter.png`, `android-cjk-tools.png`, `android-cjk-transcript.png`, `android-ime-pinyin.png`, `android-ime-pinyin-multiline.png`, `android-ime-pinyin-committed.png`, `android-chip-effort.png`, `android-chip-model.png`, `android-landscape.png`. From round 3 and not retaken: `android-keyboard.png`, `android-multiline.png`, `android-settings.png`, `android-archived.png`.

## What matches

- **CJK fallback.** Geist has no CJK glyphs. `FontChain` puts the system Noto Sans CJK SC after Geist / Geist Mono in one `CustomFallbackBuilder` chain per face. The Rust measurer, the transcript canvas, and Compose labels all use those same typefaces, so wraps and row heights agree with the drawing. The session list, header title, bubble, prose, and tool rows render Chinese and Japanese with real glyphs.
- **Double-width CJK in mono.** In mono runs each wide cluster measures and draws as exactly 2× the `0` advance. In `cjk-transcript.png` the `| 名称 | 状态 |` table columns line up with the ASCII rows, and `rg -n "中文标题" docs/排版` keeps its grid.
- **Tool rows** follow the iOS frame at 3×. The icon sits on the trunk. Line 1 is the verb in the secondary color, with a red “Failed” on failure. Line 2 is the argument or command in Geist Mono 13 (`TextSoft`), on one line with a trailing fade. File calls show `parent/name` (for example `shell/transcript.rs`). Short 1pt rail segments join the icons. The group summary wraps instead of truncating, and it only breaks between “·” segments, so “1 failed” stays together.
- **Inline code** is the iOS `Palette` gray: `#DCDCE0` on `#1A1A1E` in dark, `#3F3F46` on `#E9E9ED` in light. The user bubble hugs its text, is right-aligned, and ends 7/3 pt short of the column. That puts it at x≈77–1145, like iOS.
- **Chips** follow `CoreSessionSource`: model (brand mark), effort (gauge), then the PR, or the branch if there is no PR. Tapping one opens an anchored, undimmed glass menu above it, left-aligned with the chip and kept below the status bar:
  - Model: live `listModels` catalog, check on the current model; applies with `setSessionConfig`.
  - Reasoning effort: the model's levels, check on the current one.
  - PR: Open Pull Request and Copy Link.
- **Space filter.** There is no dim scrim, as in the iOS frame. The panel covers the “All” capsule: 247pt wide, radius 26, check column at 27pt, titles at 59pt, a visible divider, and a folder-plus “New space…”. It lists only spaces that have active (non-archived) sessions, most recent first, plus the selected space.
- **Chinese IME.** fcitx5-android 0.1.3 (Pinyin, from GitHub releases) was the system IME, with no custom keyboard. Measured at 3× px:
  - The IME top, including the candidate bar, is at y=1572. The composer card bottom is at y=1509, so the field stays above the inset.
  - A one-line draft card starts at y=1328. The two-line draft starts at y=1256, so the field grew upward by one line (72px) and its bottom did not move.
  - Candidates sit in fcitx's own bar inside the inset and never cover the field. fcitx draws its preedit label (“zhe dang”) in the 21dp gap just above that bar, so it touches the card's bottom edge but not the text.
  - Committed text (`我们在测试中文输入法，这个输入框会自动换行，候选栏不会遮挡。`) wraps to two lines in the field.

## Remaining differences

- **Old iOS frames.** The iOS PNGs predate the current Swift code:
  - The PNG's inline code is purple, but `Palette.swift` specifies gray, which Android follows.
  - The PNG's chip row is `#77 · main · GPT-5.6-Terra`. `CoreSessionSource` builds model, effort, then PR or branch, which Android follows.
  - The PNG's tool summary says “Ran 1 command · edited 1 file · 1 search · 1 failed”. The shared Rust summary for this fixture is “Ran 2 commands · edited 1 file · searched 1 time · 1 failed”, which wraps to two lines on the phone width. The expanded body has the second `cargo test` row.
- **Space-menu contents.** The iOS source in this repo has no space-filter menu, so the rule was inferred from the frame. That frame shows All, zeron, and edge from an older, smaller fixture. With the current fixture all four spaces (zeron, edge, Zeron iOS, blog) have active sessions, so all four are listed.
- **Branch chip menu** (Copy Branch Name) is an Android addition. iOS has no menu on the branch chip.
- **Compose mono** (branch or PR chip text, menu titles) uses the same font chain, but double-width CJK alignment is only enforced in the Rust-laid-out transcript.
- **Menus** have no live backdrop blur. They use a denser `#232325` wash at 86% instead of UIMenu's material.
- **fcitx preedit** is drawn by fcitx in its own label above the candidate bar, not inline in the field. Only fcitx's Quick Phrase mode showed an inline underlined preedit. That is IME behavior; the field accepts `setComposingText`.
- **Landscape** is full-bleed (1206px tall with the status bar). The iOS landscape frame is the queued state (two queued rows with an attachment strip), which was not reproduced. The queue, gallery, lightbox, and first-capture appshots were not recaptured.
- **Profile** is a drawn person glyph, not a photo.
