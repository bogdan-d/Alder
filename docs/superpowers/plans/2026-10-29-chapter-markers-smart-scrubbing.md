# Chapter Markers & Smart Scrubbing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add chapter markers, IntroDB visual segment cues, floating chapter title badge above the knob, and Refinement A1 smart D-pad scrubbing to the player timeline.

**Architecture:**
- **MKV Chapter Header Reader (`MkvChapters.kt`):** Pure-Kotlin, zero-dependency EBML reader making a 2-step HTTP range request (64 KB initial buffer + optional SeekHead target offset). Decoupled from Media3/Jellyfin ffmpeg-decoder versions.
- **Unified Timeline Marker Model (`TimelineMarkers.kt`):** Holds chapters, intro, recap, and outro timestamps with deduplication rules (IntroDB priority within ±5 seconds) and boundary snap navigation.
- **SeekBar Canvas Rendering (`PlayerActivity.kt`):** Draws 2 dp gaps/ticks at marker timestamps and subtle tungsten tint over intro/recap track spans.
- **Floating Chapter Pill & Refinement A1 Navigation (`PlayerActivity.kt`):** Floating badge above knob displaying chapter/segment title. Normal mode allows fine scrubbing; pressing `Up` enters `chapterSnapMode`, highlighting the pill and allowing `Left`/`Right` to snap cleanly between chapter boundaries.

**Tech Stack:** Kotlin, Android View / Canvas Framework, Media3 1.9.0, JUnit 4

**Spec:** `docs/superpowers/specs/2026-10-29-chapter-markers-smart-scrubbing-design.md`

## Global Constraints
- Native Android View and Canvas framework only; no external UI libraries.
- Zero dependencies added to `build.gradle.kts`. Do not change Media3 or Jellyfin decoder versions.
- Asynchronous networking in `background {}` only; fail-safe `runCatching` with zero risk of crashing or stalling playback.
- Follow Alder's "House Lights" design system (`TUNGSTEN` `#F3D9B1`, `TUNGSTEN_RIM` `#99F3D9B1`, `TUNGSTEN_GLOW` `#59F3D9B1`).
- ASD-STE100 compliance for `CHANGELOG.md` (no "-ing" words, active voice, short bold titles).
- Native Codex only: no Mobile MCP or external model tooling.

---

### Task 1: MKV EBML Header Chapter Reader

**Files:**
- Create: `app/src/main/java/dev/alder/MkvChapters.kt`
- Create: `app/src/test/java/dev/alder/MkvChaptersTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class MkvChapter(val startMs: Long, val title: String)
  object MkvChapters {
      fun parseEbml(bytes: ByteArray, segmentDataStart: Long = 0L): ParseResult
      fun fetch(url: String, headers: Map<String, String> = emptyMap()): List<MkvChapter>
  }
  ```

- [ ] **Step 1: Write unit tests in `MkvChaptersTest.kt`**
  Write tests covering:
  - Reading EBML variable-length integers (VINT: 1-byte, 2-byte, 4-byte).
  - Parsing a synthetic MKV byte buffer containing `EBMLHeader`, `Segment`, and direct `Chapters` (`0x1043A770`) with `ChapterAtom` (`0xB6`), `ChapterTimeStart` (`0x91`), and `ChapString` (`0x85`).
  - Parsing a buffer where `SeekHead` (`0x114D9B74`) points to a chapter offset.
  - Handling empty or malformed buffers safely without throwing unhandled exceptions.

- [ ] **Step 2: Run test to verify it fails**
  Run: `.\gradlew testDebugUnitTest --tests dev.alder.MkvChaptersTest`
  Expected: FAIL (class not found).

- [ ] **Step 3: Implement `MkvChapters.kt`**
  Implement:
  - `VintReader` helper for reading EBML IDs and lengths.
  - Big-endian integer reader (nanoseconds to milliseconds: `ns / 1_000_000L`).
  - SeekHead seeker for `0x1043A770`.
  - ChapterAtom extractor (`ChapterTimeStart`, `ChapterFlagHidden`, `ChapterDisplay` -> `ChapString`).
  - HTTP Range request caller using `Http.open(url, headers)` with `Range: bytes=0-65535` and follow-up range request if `SeekPosition` is outside the initial chunk.
  - Skip non-MKV streams (`.m3u8`, explicit `.mp4`).

- [ ] **Step 4: Run test to verify it passes**
  Run: `.\gradlew testDebugUnitTest --tests dev.alder.MkvChaptersTest`
  Expected: PASS.

- [ ] **Step 5: Commit**
  ```bash
  git add app/src/main/java/dev/alder/MkvChapters.kt app/src/test/java/dev/alder/MkvChaptersTest.kt
  git commit -m "MkvChapters: extract chapters from Matroska EBML container headers"
  ```

---

### Task 2: Timeline Marker Data Model & Deduplication

**Files:**
- Create: `app/src/main/java/dev/alder/TimelineMarkers.kt`
- Create: `app/src/test/java/dev/alder/TimelineMarkersTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class MarkerType { CHAPTER, INTRO, RECAP, CREDITS }
  data class Marker(val timeMs: Long, val title: String, val type: MarkerType, val endMs: Long? = null)
  object TimelineMarkers {
      fun merge(
          mkvChapters: List<MkvChapter>,
          introSegments: List<PlayerActivity.Segment>,
          outroAt: Long,
          durationMs: Long
      ): List<Marker>
      fun findCurrent(markers: List<Marker>, timeMs: Long): Marker?
      fun findNext(markers: List<Marker>, timeMs: Long): Marker?
      fun findPrev(markers: List<Marker>, timeMs: Long): Marker?
  }
  ```

- [ ] **Step 1: Write unit tests in `TimelineMarkersTest.kt`**
  Test:
  - Deduplication: MKV chapter at 1:15 is overridden by IntroDB Intro at 1:12 (within 5 seconds).
  - Outro/Credits mapping from `outroAt`.
  - Prepending "Start" at `0L` if no chapter starts at 0:00.
  - `findCurrent`, `findNext`, `findPrev` boundary snapping with threshold tolerance.

- [ ] **Step 2: Run test to verify it fails**
  Run: `.\gradlew testDebugUnitTest --tests dev.alder.TimelineMarkersTest`
  Expected: FAIL.

- [ ] **Step 3: Implement `TimelineMarkers.kt`**
  Implement data classes and `TimelineMarkers` object methods.

- [ ] **Step 4: Run test to verify it passes**
  Run: `.\gradlew testDebugUnitTest --tests dev.alder.TimelineMarkersTest`
  Expected: PASS.

- [ ] **Step 5: Commit**
  ```bash
  git add app/src/main/java/dev/alder/TimelineMarkers.kt app/src/test/java/dev/alder/TimelineMarkersTest.kt
  git commit -m "TimelineMarkers: merge MKV chapters with IntroDB segment markers"
  ```

---

### Task 3: SeekBar Track Cutouts & Visual Landmarks

**Files:**
- Modify: `app/src/main/java/dev/alder/PlayerActivity.kt:1467-1501`

**Interfaces:**
- Consumes: `Marker`, `MarkerType` from `TimelineMarkers.kt`
- Modifies: `SeekBar` in `PlayerActivity.kt`
  ```kotlin
  fun setMarkers(markers: List<Marker>)
  ```

- [ ] **Step 1: Update `SeekBar` in `PlayerActivity.kt`**
  - Add `private var markers = emptyList<Marker>()`.
  - Implement `fun setMarkers(markers: List<Marker>) { this.markers = markers; invalidate() }`.
  - In `onDraw(canvas)`:
    - For each marker with `endMs != null` (e.g. Intro / Recap): draw subtle tinted track section (`0x33F3D9B1`) from `x(startMs)` to `x(endMs)`.
    - For each marker `m`: draw a 2 dp hairline gap at `x(m.timeMs)` with `0xFF0E0C0A` (background color) to create clean visual chapter notches.

- [ ] **Step 2: Verify compilation and tests**
  Run: `.\gradlew assembleDebug`
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**
  ```bash
  git add app/src/main/java/dev/alder/PlayerActivity.kt
  git commit -m "PlayerActivity: render 2 dp chapter ticks and intro track highlights on SeekBar"
  ```

---

### Task 4: Floating Chapter Pill UI & Dynamic Position Updates

**Files:**
- Modify: `app/src/main/java/dev/alder/PlayerActivity.kt`

**Interfaces:**
- Modifies: `buildControls()`, `updateProgress()`, and `hideControls()` in `PlayerActivity.kt`.

- [ ] **Step 1: Add `chapterPill: TextView` in `buildControls()`**
  - Create `chapterPill = text("", SMALL, color = TEXT_2).onArt()` with 10 dp horizontal padding, 4 dp vertical padding, and rounded background.
  - Position it directly above `bar` in `controls`.
  - Initial visibility: `View.INVISIBLE`.

- [ ] **Step 2: Update `chapterPill` in `updateProgress()`**
  - When `bar.isFocused` or `scrubTo >= 0`:
    - Find active marker: `val active = TimelineMarkers.findCurrent(markers, position)`.
    - If active != null: set `chapterPill.text = active.title; chapterPill.visibility = View.VISIBLE`.
    - Center `chapterPill.translationX = (x(position) - chapterPill.width / 2f).coerceIn(dp(PAD).toFloat(), (controls.width - dp(PAD) - chapterPill.width).toFloat())`.
  - When not focused and not scrubbing: hide `chapterPill`.

- [ ] **Step 3: Verify compilation**
  Run: `.\gradlew assembleDebug`
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**
  ```bash
  git add app/src/main/java/dev/alder/PlayerActivity.kt
  git commit -m "PlayerActivity: add floating chapter title pill above SeekBar knob"
  ```

---

### Task 5: Refinement A1 D-Pad Navigation & Chapter Snapping

**Files:**
- Modify: `app/src/main/java/dev/alder/PlayerActivity.kt`

**Interfaces:**
- Modifies: `bar.setOnKeyListener`, `updateProgress()`, and `onKeyDown` in `PlayerActivity.kt`.

- [ ] **Step 1: Add `chapterSnapMode` state to `PlayerActivity`**
  - `private var chapterSnapMode = false`.
  - Add helper function `private fun setChapterSnapMode(enabled: Boolean)`:
    - Updates `chapterSnapMode`.
    - Toggles pill styling: in snap mode, gives pill a tungsten rim (`TUNGSTEN_RIM` `#99F3D9B1`), text color `TUNGSTEN` (`#FFF3D9B1`), and glow. In normal mode, subtle dark glass.
    - Calls `bar.invalidate()`.

- [ ] **Step 2: Implement key handling on `bar.setOnKeyListener`**
  - When `chapterSnapMode == false`:
    - `DPAD_UP`: `setChapterSnapMode(true)`; snaps `scrubTo` to current/nearest chapter; returns `true`.
    - `DPAD_LEFT` / `RIGHT`: Standard fine scrubbing (`scrub(...)`); returns `true`.
    - `DPAD_DOWN`: If `scrubTo >= 0`, cancels scrub (`scrubTo = -1L`); returns `false` to move to `playPause.id`.
  - When `chapterSnapMode == true`:
    - `DPAD_RIGHT`: Snaps `scrubTo` to `TimelineMarkers.findNext(markers, currentPos)`; calls `updateProgress()`; returns `true`.
    - `DPAD_LEFT`: Snaps `scrubTo` to `TimelineMarkers.findPrev(markers, currentPos)`; calls `updateProgress()`; returns `true`.
    - `DPAD_DOWN`: `setChapterSnapMode(false)`; returns `true`.
    - `DPAD_CENTER` / `ENTER`: Commits seek immediately to current chapter position; `setChapterSnapMode(false)`; returns `true`.

- [ ] **Step 3: Handle BACK key in `dispatchKeyEvent`**
  - If `shown && chapterSnapMode && event.keyCode == KEYCODE_BACK`:
    - `setChapterSnapMode(false)`; return `true` (cancels snap mode without dismissing controls).

- [ ] **Step 4: Verify compilation and tests**
  Run: `.\gradlew testDebugUnitTest`
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**
  ```bash
  git add app/src/main/java/dev/alder/PlayerActivity.kt
  git commit -m "PlayerActivity: implement Refinement A1 chapter snap navigation on remote D-pad"
  ```

---

### Task 6: Wire Asynchronous Chapter & Segment Extraction

**Files:**
- Modify: `app/src/main/java/dev/alder/PlayerActivity.kt`

**Interfaces:**
- Modifies: `load()`, `fetchSegments()`, and `next()` in `PlayerActivity.kt`.

- [ ] **Step 1: Asynchronous loading in `load()`**
  - In `load()`, reset `markers = emptyList()`, `bar.setMarkers(emptyList())`, `chapterSnapMode = false`.
  - In `background {}`, call `val mkvChapters = MkvChapters.fetch(s.url, s.headers)`.
  - When chapters or IntroDB segments arrive, combine via `TimelineMarkers.merge(...)` and dispatch to `main { bar.setMarkers(merged); updateProgress() }`.

- [ ] **Step 2: Clean up on stream change**
  - In `next()`, ensure any pending background chapter fetches or seek tasks are cleared.

- [ ] **Step 3: Run full unit test suite**
  Run: `.\gradlew testDebugUnitTest`
  Expected: All tests pass.

- [ ] **Step 4: Commit**
  ```bash
  git add app/src/main/java/dev/alder/PlayerActivity.kt
  git commit -m "PlayerActivity: wire asynchronous MKV chapter parsing and IntroDB merge"
  ```

---

### Task 7: CHANGELOG.md Documentation

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add ASD-STE100 entry**
  Under `## Unreleased` › `### New`:
  ```markdown
  - **Chapter markers and smart scrubbing.** See chapters and intro markers on the timeline. Press Up on the progress bar to snap between chapters.
  ```

- [ ] **Step 2: Commit**
  ```bash
  git add CHANGELOG.md
  git commit -m "docs: add chapter markers and smart scrubbing to CHANGELOG"
  ```

---

## Plan Review Checklist
- [x] **Spec coverage:** All sections of the design spec (EBML reader, timeline marker merge, 2 dp ticks, floating chapter pill, Refinement A1 D-pad navigation, fail-safe async) have dedicated tasks.
- [x] **No Placeholders:** Every task has explicit file paths, signatures, test definitions, and step-by-step actions.
- [x] **Type consistency:** `Marker`, `MarkerType`, `MkvChapter`, `TimelineMarkers`, and `setChapterSnapMode` match across all tasks.
