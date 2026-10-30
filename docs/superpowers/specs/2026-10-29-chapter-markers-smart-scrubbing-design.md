# Alder: Chapter Markers & Smart Scrubbing Design

**Approved:** 2026-10-29  
**Status:** Approved for Implementation  
**Topic:** Chapter Markers, IntroDB Integration, and Smart Scrubbing on Player SeekBar  

---

## 1. Overview & Goals

Provide visual landmarks and seamless chapter navigation along the player timeline in `PlayerActivity.kt`. Viewers can identify intro/credits boundaries and container chapters, see active chapter titles above the knob, and jump cleanly between chapters using standard Android TV remote controls.

### Core Goals:
1. **Visual Landmarks ("House Lights" theme):**
   - Render subtle 2 dp gaps/ticks along the SeekBar track for chapters and segment boundaries.
   - Tint Intro/Recap segments subtly so viewers can see where intros live without scrubbing blindly.
2. **Contextual Chapter Pill:**
   - Display the current chapter title (or "Intro", "Recap", "Credits") above the warm-white knob in small typography.
   - Clamp badge coordinates to prevent off-screen clipping at early or late timestamps.
3. **Ergonomic TV Remote Navigation (Refinement A1):**
   - Preserve natural horizontal/vertical mental model:
     - Horizontal (`Left` / `Right`) = Time scrub.
     - Vertical (`Up` / `Down`) = UI layer hierarchy.
   - While focused on the SeekBar:
     - `Left` / `Right` → Standard fine-scrubbing (`scrubTo`).
     - `Down` → Moves focus down to playback controls (`playPause`).
     - `Up` → Toggles `chapterSnapMode = true`, highlighting the Chapter Pill and snapping to chapter boundaries.
   - While `chapterSnapMode == true`:
     - `Left` / `Right` → Snaps directly to previous / next chapter timestamp.
     - `Down` → Exits chapter mode back to normal SeekBar fine-scrubbing.
     - `OK` → Commits seek immediately.
4. **Resilient MKV Chapter Extraction (Decoupled EBML Reader):**
   - Media3 1.9.0 does not natively parse MKV container chapters into player metadata.
   - Implement a lightweight, zero-dependency pure Kotlin EBML header reader (`MkvChapters.kt`).
   - Use an asynchronous two-step HTTP range request (64 KB initial buffer + optional SeekHead target offset).
   - Merge container chapters with IntroDB community timestamps, giving IntroDB priority within ±5 seconds.

---

## 2. Spatial Navigation & Remote Key Handling

### Layout Hierarchy:
```
┌────────────────────────────────────────────────────────┐
│  [ Chapter Pill: "Chapter 3: The Heist" ]             │  ◄── Chapter Pill (Highlights when chapterSnapMode == true)
│                         ▲ (Up)   │ (Down)              │
│                         │        ▼                     │
│  [━━━━━━━●━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━]  │  ◄── SeekBar (bar.isFocused)
│                                  │ (Down)              │
│                                  ▼                     │
│  [ Subtitles ]   [ Stream ]   [ ▶ Play ]   [ Info ]    │  ◄── Playback Buttons (playPause)
└────────────────────────────────────────────────────────┘
```

### State Machine & Key Handling on `bar`:

1. **State: `bar.isFocused && !chapterSnapMode` (Normal Scrub Mode)**
   - `KeyEvent.KEYCODE_DPAD_LEFT` / `RIGHT`:
     - Increments/decrements `scrubTo` with standard acceleration (10s, 30s, 60s based on repeat count).
     - Chapter pill displays matching chapter name for current `scrubTo` position.
   - `KeyEvent.KEYCODE_DPAD_UP`:
     - Toggles `chapterSnapMode = true`.
     - Snaps `scrubTo` immediately to the current or nearest chapter boundary.
     - Calls `bar.invalidate()` and `updateProgress()` to highlight the chapter pill.
     - Consumes event (`return true`).
   - `KeyEvent.KEYCODE_DPAD_DOWN`:
     - If actively scrubbing (`scrubTo >= 0`), cancels scrub (`scrubTo = -1L`) and moves focus to `playPause.id`.
     - If idle, passes through to normal Android focus navigation (`nextFocusDownId = playPause.id`).
   - `KeyEvent.KEYCODE_DPAD_CENTER` / `ENTER`:
     - If scrubbing, commits seek immediately: `player.seekTo(scrubTo); scrubTo = -1L`.
     - If idle, toggles play/pause.

2. **State: `bar.isFocused && chapterSnapMode` (Chapter Snap Mode)**
   - Visual styling: Chapter Pill above the knob gains a tungsten border (`TUNGSTEN_RIM`) and subtle glow (`TUNGSTEN_GLOW`).
   - `KeyEvent.KEYCODE_DPAD_LEFT`:
     - Finds the previous chapter start (`marker.timeMs < currentScrub - 1000ms`).
     - Snaps `scrubTo` to that chapter start.
     - Consumes event (`return true`).
   - `KeyEvent.KEYCODE_DPAD_RIGHT`:
     - Finds the next chapter start (`marker.timeMs > currentScrub + 1000ms`).
     - Snaps `scrubTo` to that chapter start.
     - Consumes event (`return true`).
   - `KeyEvent.KEYCODE_DPAD_DOWN`:
     - Exits chapter snap mode (`chapterSnapMode = false`).
     - Keeps current `scrubTo` position on the SeekBar for fine adjustment.
     - Consumes event (`return true`).
   - `KeyEvent.KEYCODE_DPAD_CENTER` / `ENTER`:
     - Commits seek immediately to current chapter position.
     - Sets `chapterSnapMode = false`.
     - Consumes event (`return true`).
   - `KeyEvent.KEYCODE_BACK`:
     - Exits chapter snap mode (`chapterSnapMode = false`).
     - Consumes event (`return true`).

---

## 3. MKV EBML Chapter Reader (`MkvChapters.kt`)

### Two-Step SeekHead Range Request:
1. **Initial Range (Step 1):**
   - URL: Current streaming video URL (`sources[at].url`).
   - Request Headers: Forward `sources[at].headers` (Debrid auth, User-Agent).
   - Range header: `bytes=0-65535` (64 KB).
   - Verify EBML Header: `0x1A45DFA3` (`EBMLHeader`).
   - Locate top-level `Segment` (`0x18538067`).
   - Record `segmentDataStart` (byte position right after Segment element ID and length).

2. **Chapter Resolution (Step 2):**
   - Check if `Chapters` element (`0x1043A770`) is already present inside the 64 KB buffer.
   - If present: Parse `EditionEntry` (`0x45B9`) → `ChapterAtom` (`0xB6`) directly.
   - If not present:
     - Scan `SeekHead` (`0x114D9B74`) inside the buffer.
     - Search for `Seek` (`0x4DBB`) entry where `SeekID` (`0x53AB`) == `0x1043A770` (`Chapters`).
     - Extract `SeekPosition` (`0x53AC`).
     - Calculate target byte offset: `targetOffset = segmentDataStart + seekPosition`.
     - Make targeted 64 KB HTTP request: `Range: bytes=targetOffset-(targetOffset + 65535)`.
     - Parse `Chapters` from this second buffer.

3. **Chapter Parsing Details:**
   - `ChapterAtom` (`0xB6`):
     - `ChapterTimeStart` (`0x91`): Unsigned Big-Endian Long (stored in nanoseconds; convert: `ms = ns / 1_000_000L`).
     - `ChapterFlagHidden` (`0x98`): If `1`, ignore this atom.
     - `ChapterFlagEnabled` (`0x4598`): If `0`, ignore this atom.
     - `ChapterDisplay` (`0x80`):
       - `ChapString` (`0x85`): UTF-8 String (e.g. "Prologue", "Chapter 1", "The Heist").
   - Fallback: If `ChapString` is missing or empty, generate `"Chapter ${index + 1}"`.

4. **Fail-Safe Operation:**
   - Wrapped in `runCatching {}`. Returns `emptyList()` on any network failure, EOF, non-range response, or timeout.
   - Skipped entirely for HLS (`.m3u8`) or MP4 streams.

---

## 4. Unified Marker Model & Deduplication

### Data Structure:
```kotlin
enum class MarkerType { CHAPTER, INTRO, RECAP, CREDITS }

data class Marker(
    val timeMs: Long,
    val title: String,
    val type: MarkerType,
    val endMs: Long? = null
)
```

### Merging Logic:
1. Collect IntroDB segments from `fetchSegments()`:
   - Recap: `Segment("Recap", start, end)` -> `Marker(start, "Recap", MarkerType.RECAP, end)`
   - Intro: `Segment("Intro", start, end)` -> `Marker(start, "Intro", MarkerType.INTRO, end)`
   - Outro: `outroAt` -> `Marker(outroAt, "Credits", MarkerType.CREDITS, duration)`
2. Collect MKV chapters from `MkvChapters.fetch()`.
3. Filter out any MKV chapter whose `timeMs` is within **±5,000 ms** of an IntroDB marker (IntroDB label takes precedence).
4. Combine and sort strictly by `timeMs`.
5. Ensure a marker exists at `0L` (if first marker > 10,000 ms, prepend `Marker(0L, "Start", MarkerType.CHAPTER)`).

---

## 5. UI Canvas & "House Lights" Presentation

### 1. Track Cutouts on `SeekBar.onDraw`:
- At each marker `m` in `markers`:
  - Calculate `val mx = x(m.timeMs)`.
  - Draw 2 dp hairline gap using `paint.color = 0xFF0E0C0A.toInt()` (background shade) from `y - line/2` to `y + line/2`.
- Intro / Recap highlighting:
  - If a marker has `endMs != null` (e.g. Intro / Recap), draw a subtle tungsten tint (`0x33F3D9B1`) over the track between `x(startMs)` and `x(endMs)`.

### 2. Floating Chapter Title Pill:
- A `TextView` positioned right above the `SeekBar`:
  - Font: `SMALL` (12 sp), Instrument Sans.
  - Padding: `dp(10)` horizontal, `dp(4)` vertical.
  - Background:
    - In normal mode: subtle dark glass `0x800E0C0A` with text `TEXT_2` (`0xB3FFFFFF`).
    - In `chapterSnapMode`: tungsten rim `TUNGSTEN_RIM` (`0x99F3D9B1`) with warm text `TUNGSTEN` (`0xFFF3D9B1`) and subtle glow.
  - Horizontal Alignment: Centered at `x(position)`. Clamped between `dp(PAD)` and `width - dp(PAD) - pillWidth`.
  - Visibility: Visible when `bar.isFocused` or actively scrubbing; invisible when controls hide or focus moves to button row.

---

## 6. Verification & Test Plan

1. **Unit Tests (`MkvChaptersTest.kt`):**
   - VINT decoding tests (1-byte to 8-byte variable integers).
   - Mock EBML byte stream parsing with standard headers, SeekHead references, and ChapterAtoms.
   - Hidden chapter filtering (`ChapterFlagHidden == 1`).
   - Timestamp nanosecond-to-millisecond conversion.
2. **Unit Tests (`MarkerMergeTest.kt`):**
   - IntroDB vs MKV chapter deduplication within ±5 seconds.
   - Sorting order and fallback to "Start" at 0:00.
3. **Manual Player Verification:**
   - Stream an MKV REMUX: Verify chapter ticks appear and titles show above knob.
   - Stream a TV episode with IntroDB data: Verify Intro / Recap segments are labeled and highlighted.
   - Test D-pad navigation:
     - `Left` / `Right` scrubs smoothly.
     - `Up` enters chapter snap mode; `Left` / `Right` jumps between chapters.
     - `Down` returns to fine-scrub; second `Down` moves to `playPause`.
