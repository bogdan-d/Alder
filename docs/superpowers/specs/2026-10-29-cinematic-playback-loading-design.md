# Design Spec: Cinematic Playback Loading & Theater Dimming

## Status: Approved
**Author:** AI Assistant & aradhya
**Date:** 2026-10-29
**Branch:** `feature/cinematic-playback-loading`

---

## 1. Context & Motivation

When a user selects "Play" or "Resume" on a title in Alder (`stream-tv`), playback initiation currently suffers from several jarring visual discontinuities:

1. **Details Screen Popup Veil:** `DetailsActivity` and `MainActivity` present a crude semi-transparent dark veil (`0x800E0C0A`) with a circular spinner slap-bang in the center over the title artwork, poster, and metadata chips, declaring `"Finding sources…"`.
2. **Abrupt Black Void:** Once streams are resolved (~1.5–3 seconds later), the screen cuts immediately to an empty pitch-black canvas in `PlayerActivity`.
3. **Disjointed Loader & Bar Location:** In `PlayerActivity`, another generic spinner appears over the black void with raw technical labels (`Starting · Torrentio · 2160p · 22 GB...`). Previously, progress bars appeared at the top of the screen rather than being naturally anchored at the bottom where all media controls reside.
4. **Perceived Latency:** The viewer waits through two disjointed loading states (source discovery + ExoPlayer buffer), making the 4–7 second startup feel long and unpolished.

---

## 2. Design Vision: House Lights "Theater Dimming"

Alder's design philosophy is **"House Lights"**: warm cinematic tungsten glow (`#F3D9B1`), warm off-white typography (`#F3EEE6`), rich charcoal dark base (`#0E0C0A`), and subtle ambient lighting. 

When a movie starts in a theater, the house lights gently dim, the curtain rises, the title appears, and the film begins. We bring that exact mental model to Alder.

### User Experience Lifecycle

```
[ User presses "Play" / "Resume" on Details or Home ]
                   │
                   ▼ (Immediate transition — 0ms delay)
[ Cinematic Player Stage Opens ]
   • High-resolution title backdrop art fills the screen, shaded by House Lights vignette.
   • Title logo / Instrument Serif title & episode subtitle appear at bottom-left.
   • Bottom Seek Bar is already situated in its final home at Gravity.BOTTOM.
   • Status in bottom metadata row reads "Finding sources…".
   • Seek bar track displays a warm, gentle tungsten pulse/glow.
                   │
                   ▼ (Sources resolved in background)
[ Stream Matched (~1.5s) ]
   • Release quality badges illuminate: [ 4K ] [ Dolby Vision ] [ Atmos ] [ Real-Debrid ]
   • Status updates: "Starting stream…"
   • ExoPlayer prepares media stream in the background.
                   │
                   ▼ (First video frame rendered)
[ Cross-Fade to Film (~3.0s) ]
   • Backdrop artwork softly cross-fades (350ms alpha animation) into the live video stream.
   • Bottom controls smoothly fade out into full-screen cinema.
```

---

## 3. Architecture & Key Changes

### 3.1 Transition from `DetailsActivity` & `MainActivity`
- Pressing "Play" or "Resume" starts `PlayerActivity` immediately without displaying any full-screen pop-up veil.
- `PlayerActivity` receives the title metadata (`id`, `type`, `name`, `background`, `logo`, `poster`, `episode`, `videoId`, `resumeAt`).
- If `sources` are already known (e.g. from `StreamsActivity`), `PlayerActivity` loads them directly. If `sources` are absent, `PlayerActivity` invokes `StreamsResolver.resolve()`.

### 3.2 Stage Layout in `PlayerActivity`
1. **Backdrop Layer (`loadingBackdrop` & `loadingShade`):**
   - Placed directly above the `PlayerView` and below `controls`.
   - Loads the title's high-res backdrop art (or poster as fallback) using `Images.load`.
   - Applies standard House Lights `windowShade` gradient (left-to-right dark gradient for text legibility, bottom-to-top gradient for controls legibility).
2. **Bottom Controls Integration:**
   - Instead of a detached loading view or top progress bar, the existing `controls` layout is utilized during startup.
   - `pauseTitle`, `subtitle`, and `playerLogo` display the title branding.
   - `pauseFacts` displays the startup status (`"Finding sources…"`, `"Starting stream…"`).
   - `bar` (bottom `SeekBar`) stays firmly in place at `Gravity.BOTTOM` with a pulsing tungsten glow on its track during indeterminate buffering.
   - `pauseBadges` displays the release badges as soon as the source is resolved.
3. **Dismissal of Loading Stage:**
   - Once ExoPlayer triggers `onRenderedFirstFrame` (or playback actually begins rendering frames), `loadingBackdrop` fades out with an alpha animation (350ms duration) and is hidden.
   - `controls` fade away as normal when playback starts.

### 3.3 Cancellation & Error Handling
- **Back Key Pressed During Loading:**
  - If the user presses `KEYCODE_BACK` while sources are still resolving or the stream is buffering, `PlayerActivity` cancels `StreamsResolver` / ExoPlayer loading and immediately finishes, cleanly returning to Details or Home.
- **Source Resolution Fallback:**
  - If `StreamsResolver` finds no playable streams or falls back, `PlayerActivity` launches `StreamsActivity` and finishes cleanly so the user can inspect or pick streams manually.
- **Source Playback Error:**
  - If the selected source fails to play, `PlayerActivity` automatically tries the next best source with status `"That source failed · trying the next one"` before falling back to `StreamsActivity`.

---

## 4. Testing & Verification

1. **Unit & Logic Tests (`StreamsResolverTest`, `ScreenDesignTest`):**
   - Verify intent extras parsing and badge formatting for streams.
   - Verify `StreamsResolver` lifecycle and cancellation handling.
2. **UI & Layout Verification:**
   - Ensure the seek bar position is strictly at the bottom of the screen (`Gravity.BOTTOM`), never at the top.
   - Verify backdrop art and shade view hierarchies.
   - Verify alpha fade-out transition on first frame rendering.
3. **Regression Tests:**
   - Check trailers (trailer playback should start without stream resolution).
   - Check manual stream pick (`pick = true` opens `StreamsActivity` directly).
   - Check resume point preservation.
