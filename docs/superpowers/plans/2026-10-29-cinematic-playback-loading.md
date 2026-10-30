# Cinematic Playback Loading & Theater Dimming Implementation Plan

Implement a continuous, cinematic "House Lights" theater dimming experience when starting video playback in Alder (`stream-tv`). Remove jarring modal overlays on title pages and cold black loading screens. Transition immediately to a backdrop-lit player stage with bottom-anchored controls, badges, and progress bar.

## User Review Checkpoints
- **Checkpoint 1:** Design spec approved in `docs/superpowers/specs/2026-10-29-cinematic-playback-loading-design.md`.
- **Checkpoint 2:** Implementation of Player stage and stream resolution lifecycle completed.
- **Checkpoint 3:** Details & Main activity transitions streamlined.
- **Checkpoint 4:** All tests pass, build verified, CHANGELOG updated, and PR created.

---

## Proposed Changes

### 1. `app/src/main/java/dev/alder/PlayerActivity.kt`
- Add backdrop art view (`loadingBackdrop`) with House Lights cinematic vignette over the player view.
- When `sources` extra is absent in intent, initiate `StreamsResolver.resolve()` directly within `PlayerActivity`.
- Anchor all loading feedback in the bottom controls: title logo, episode title, metadata badges (`4K`, `HDR`, `Dolby Vision`, `Atmos`, `Real-Debrid`), and the bottom seek bar (`bar`).
- Add warm tungsten breathing pulse to the bottom seek bar while loading/buffering.
- On `onRenderedFirstFrame` (or first video picture), fade out `loadingBackdrop` over 350ms into live video and gracefully hide controls.
- Handle Back button to cleanly cancel stream resolution/buffering and finish back to Details/Home.

### 2. `app/src/main/java/dev/alder/DetailsActivity.kt`
- On "Play" or "Resume" click, immediately launch `PlayerActivity` with all title extras (`background`, `logo`, `poster`, `name`, `episode`, `resumeAt`, etc.).
- Remove the full-screen `loadingOverlay` pop-up veil for direct play.
- Preserve manual stream selection (`pick = true` / `Settings.alwaysList`) going to `StreamsActivity`.

### 3. `app/src/main/java/dev/alder/MainActivity.kt`
- Same as `DetailsActivity`: launch `PlayerActivity` immediately on cinema / continue watching play, without blocking pop-up veil.

### 4. Tests
- Add tests in `app/src/test/java/dev/alder/ScreenDesignTest.kt` or a dedicated `PlaybackLoadingTest.kt` verifying:
  - Bottom seek bar position (`Gravity.BOTTOM`) and absence of top loading bars.
  - Initial badges and status resolution logic.
  - Resolution lifecycle states.

### 5. `CHANGELOG.md`
- Document the new cinematic theater transition in ASD-STE100 English under `## Unreleased` > `### Easier to use`.

---

## Verification Plan

### Automated Tests
- `./gradlew test` (Run all unit tests in the project).
- Verify `ScreenDesignTest` and any new tests pass.

### Manual Verification Steps (via code inspection & screen layout checks)
- Verify `loadingBackdrop` is in `screen` view hierarchy between `PlayerView` and `controls`.
- Verify `bar` remains at `Gravity.BOTTOM` with `bottomMargin = dp(111)`.
- Verify no view in `PlayerActivity` or `loadingOverlay` uses `Gravity.TOP` for progress bars.
