# DraftKeys — Feature Backlog (Phases 1, 5, 6, 7)

These phases are deferred after Phases 2–4 are complete.

---

## Phase 1 — Draft History UI

**Status**: Deferred  
**Effort**: Medium | **Impact**: High

- `DraftHistoryActivity.kt` — full-screen Activity showing all saved drafts
  - Grouped by app (with app icon + name via PackageManager)
  - Each card: preview text (2 lines), relative timestamp ("2 min ago"), app name
  - Tap → copy to clipboard | Long-press → delete single draft
  - "Clear All" option in overflow menu
  - Search bar at top
- `activity_draft_history.xml` — dark theme list layout
- `item_draft.xml` — Material card with accent border
- Modify `activity_main.xml` — add "View Drafts" button
- Modify `keyboard_view.xml` — add 📝 toolbar button to launch DraftHistoryActivity
- Add intent to `AndroidManifest.xml` for DraftHistoryActivity

---

## Phase 5 — Emoji Keyboard

**Status**: Deferred  
**Effort**: Medium | **Impact**: Medium

- Third keyboard layout (EMOJI mode added to `KeyboardLayoutType`)
- `EmojiKeyboardView.kt` — custom RecyclerView grid of emoji
- Category tabs: 😀 Recent | 😄 Smileys | 🐱 Animals | 🍕 Food | ⚽ Sports | 🚗 Travel | 💡 Objects | 🔣 Symbols | 🏳️ Flags
- Recently-used emoji row persisted in SharedPreferences (last 30)
- Emoji search by Unicode name (stored in `assets/emoji_index.txt`)
- Add emoji toggle button to keyboard bottom row
- Modify `KeyboardService.kt` — handle emoji layout switching + emoji commit

---

## Phase 6 — Theming & Customization

**Status**: Deferred  
**Effort**: Low–Medium | **Impact**: Medium

- `SettingsActivity.kt` + `activity_settings.xml`
- `ThemeManager.kt` — loads/saves + applies theme
- Theme presets (stored in SharedPreferences):
  - 🌑 Midnight Blue (current default)
  - ⬛ Pure Black (AMOLED saver)
  - 🌊 Ocean Teal (#00BCD4 accent)
  - 🌅 Sunset Warm (#FF7043 accent)
  - 💜 Neon Purple (#CE93D8 accent)
- Custom accent color picker (HSV color wheel)
- Key shape options: Rounded (default) / Square / Pill
- Font size: Small (14sp) / Medium (16sp, default) / Large (18sp)
- Key height: Compact (44dp) / Normal (52dp) / Tall (60dp)
- Modify `DraftKeysView.kt` — read theme from `ThemeManager`
- Modify `SuggestionBarView.kt` — same
- Add Settings button to `MainActivity`

---

## Phase 7 — Quality of Life Improvements

**Status**: Deferred  
**Effort**: Low | **Impact**: High

Each item is an independent sub-task:

| Feature | Notes |
|---|---|
| **Double-tap Shift = CAPS LOCK** | Track last shift time; second tap within 400ms → `isCapsLock = true`; triple-tap to exit |
| **Long-press key alternates** | Hold `e` → popup showing `é è ê ë ě`; hold `.` → `.com .net .org .io`; hold `'` → `" \` \` |
| **Smart punctuation** | Type `(` → auto-insert `)` with cursor between; same for `"`, `'`, `[`, `{` |
| **Enter key action labels** | Read `EditorInfo.imeOptions`; show "Send" / "Search" / "Go" / "Next" / "Done" instead of ↵ |
| **Undo / Redo** | Stack of text snapshots; toolbar ↩ and ↪ buttons |
| **Sound feedback** | Optional click sounds via `SoundPool`; toggle in settings |
| **Landscape layout** | Detect landscape orientation; reduce key height to 40dp; optional split layout |
| **Number row long-press** | Long-press `1` → `!`; `2` → `@`; `3` → `#`; etc. |
