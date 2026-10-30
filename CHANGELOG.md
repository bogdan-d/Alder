# Changelog

Write every entry in ASD-STE100 Simplified Technical English (STE):

- Use only the approved words of the STE dictionary, with their approved meanings. You can also use technical names: Alder, Stremio, add-on, subtitle, and the names that Alder shows on the screen.
- Write one idea in each sentence. Write 20 words or fewer in an instruction and 25 words or fewer in a description.
- Use the active voice. Use the simple present tense, or the simple past tense for "Before, …".
- Do not use words that end in "-ing", except in technical names.
- Do not use contractions. Write "does not", not "doesn't". Text that Alder shows on the screen stays as it is.
- Write instructions as commands: "Select **Subtitles**", not "**Subtitles** can be selected".
- Use one word for one thing. Use the names that Alder shows on the screen.
- Put each entry under one heading: `### New`, `### Easier to use` or `### Fixes`, in that order. In each group, put the most important entries first.
- Start an important entry with a short **bold** name. **See all changes** shows all entries under their headings.
- Put `<!-- Spotlight -->` before a change to show it in the short What's new list. Put the most important spotlight changes first in each group.

Put changes that are not released under `## Unreleased`. Before each release, read all of `## Unreleased` again and rewrite each entry that does not obey these rules. Then rename that heading to the version number.

## Unreleased

### New
<!-- Spotlight -->
- **Alder in your language.** Alder shows menus, buttons and settings in Spanish, Portuguese, French, German, Hindi or Italian. Alder uses the TV language. To change it, select **Settings → Language → App language**.
<!-- Spotlight -->
- **Trailers on the title page.** Select **▷ Trailer** on a movie or show to play its trailer. Alder does not add trailers to your watch history.
- **Subtitle colors.** Choose White, Yellow, Cyan, Cream, or Warm white in **Settings → Subtitles** or while you watch in **Audio & subtitles**.
<!-- Spotlight -->
- **Live TV and sport.** If your add-ons have TV channels, sport or events, select **Live** at the top. Select a channel to play it. To choose a source, hold OK.
<!-- Spotlight -->
- **Directors and cast.** See directors and lead actors on the title screen. Select a person to see their other works. A label shows the title and year of the focused poster. Select **ⓘ** to see the full story and all cast members with portraits.
<!-- Spotlight -->
- **Chapter markers and chapter snap.** See chapter marks and intro marks on the progress bar. Press Up on the bar to jump between chapters.
<!-- Spotlight -->
- **Clear dialogue.** Turn on **Settings → Clear dialogue** to boost voices above music and loud effects on TV speakers. You can also turn it on while you watch.
- **Foreign audio subtitles.** Alder can start subtitles automatically only when the audio is in a different language. Select **Settings → Subtitles → Foreign audio only**.
- **Still watching?** If you do not press a remote button for 3 episodes, Alder stops before the next episode. Select **Play** or **Exit to Home**.
- **Sleep timer.** In the player, select **Audio & subtitles**, then select **Sleep**. Choose **After this episode** or **In 60 min**.
- **Mark episodes as watched.** Hold OK on an episode in the episode guide. Select **Mark as watched**, **Mark as unwatched**, or **Mark previous episodes watched**. Hold OK on a season button to mark all episodes of that season.
- **New episode tags.** A tungsten tag shows on Continue Watching when a new episode is available.
- **Protect unreleased episodes.** Alder shows the air date for future episodes and disables playback until they air.
- **Default tab.** Choose which tab opens when you start Alder. Select **Settings → Profile → Default tab**.
- **Show or hide tabs.** Turn tabs on or off in **Settings → Profile**.
- **Featured rows on Home.** Show top recommendations and popular titles below Continue Watching. Turn on **Settings → Profile → Featured on Home**.
- **Hide rows.** Hold OK on a title in a row, then select **Hide row**. To put it back, select **Settings → Profile → Hidden rows**.
- **30 GB file limit.** Select **30 GB** in **Settings → Largest file**.
- **Blur episode thumbnails.** Turn on **Settings → Profile → Blur episode thumbnails**. Alder blurs pictures of unwatched episodes to prevent spoilers.

### Easier to use
<!-- Spotlight -->
- **House Lights logos.** Alder uses the tungsten mark on the TV banner, transparent app icon, and small transparent logo.
- **Content ratings on the title page.** A badge shows the content rating, for example PG-13, R, or TV-MA, before the year.
- **Compact Up next prompt.** The Up next card uses a smaller width, smaller text, and a single Next episode button.
- **QWERTY Search keys.** The Search keyboard uses QWERTY letter rows. The microphone icon stays dim until voice search is available.
- **Audio and subtitle tabs.** Select **Subtitles**, **Audio**, or **Sync & Style**. Active, preferred, and recent tracks appear first. Adjust subtitle sync in the panel.
- **Clearer title page.** Cast names stay clear in dark glass capsules.
- **Clearer menus.** Add-on lists, Search, and choice menus use glass that is easy to read.
- **Clearer player controls.** The control bar shows more video. Player tags and panels use dark glass.
- **What's new showcase.** The update screen shows important changes first. Select **See all changes** to read the full list.
<!-- Spotlight -->
- **Direct play.** When you select Play, Alder starts the video directly. A progress indicator shows while Alder finds the best source. To see all sources, hold OK.
<!-- Spotlight -->
- **Cinematic video start.** When you select Play, Alder shows the title art and bottom controls immediately. The progress bar pulses with warm light while Alder connects to the source, and the video fades in smoothly.
- **Format and debrid badges in the player.** Open the player controls to see badges for resolution, Dolby Vision, HDR, Dolby Atmos, DTS, audio language, subtitles, and your debrid service below the title.
- **Faster access to Audio & subtitles.** Press Left once from Play / Pause in the player to open **Audio & subtitles**.
- **Live subtitle sync.** Adjust subtitle timing with Left and Right while the video plays. This works for embedded subtitles and add-on subtitles.
- **Close player controls.** Press Back to close the controls while the video plays. Press Back again to leave the player.
- **Exact subtitle remembered.** When you play a video again, Alder uses the exact subtitle track that you selected before.
- **Update notification dot.** A dot on your profile avatar shows when a new version of Alder is ready.
- **Install an update from the profile menu.** Select your profile avatar, then select **Install update**. Alder installs version 1.3 over version 1.2.
- **Faster update checks.** Alder checks for new releases every 4 hours instead of once a day.

### Fixes
- **Subtitle language list in Audio & subtitles.** All add-on subtitle languages now appear immediately under Subtitles. Quick timing sits below the language list so remote focus moves smoothly.
- **Up next episode card timing.** When credit timing is present, Alder shows Up next only after the credits start. Fallback window now triggers during the final 15 to 25 seconds instead of a minute early.
- **Skip intro button timing.** Alder reads MKV intro chapters first. Alder filters low confidence IntroDB segments and stops false zero second prompts when cold opens exist.
- **Remote focus and scrolling in Audio & subtitles.** Selecting an audio or subtitle track keeps remote focus on the selected track and does not reset the scroll position. Scrolling between tracks operates smoothly and clips cleanly within the scroll viewport.
- **Smoked glass overlay in Audio & subtitles.** The Audio & subtitles sheet uses the Alder smoked glass recipe for high contrast over video.
- **Cleaner title page.** The light around **Play** and **Resume** sits in the center. Cast names stay inside the screen margins. Action buttons align in the center.
- **Install on Fire TV.** Alder installs on Fire TV and Fire OS devices without a package parse error.
- **Support older TV devices.** Alder supports Android 6.0 and Fire OS 6 devices, such as Fire TV Stick 4K.
- **Cleaner player controls.** The audio, subtitle, format, and debrid badges sit in one row below the title, and the top progress bar no longer appears while you seek.
- **Back button on Android 16.** The Back button closes panels and player controls correctly on Android 16.

## 1.2

### New
- **Sources in your language.** Select a language above the source list. Alder shows only the sources in that language.
- **All audio and subtitle languages.** Select them in **Settings**. Each profile keeps its own languages.
- **Alder keeps your player choices.** The next episode starts with the audio and the subtitles that you selected.
- **Remove titles from Continue Watching.** Hold OK on a title. To put it back, select **Settings → Profile → Hidden from Continue Watching**.
- **See the debrid service.** The source list and the player show the service, for example **RD** for Real-Debrid.

### Easier to use
- **Help when there is no source.** Alder tells you why. Scan the QR code to set up the add-on on your phone. Then select **Try again**.
- **See your subtitles quickly.** The player shows the audio and the subtitles that play.
- **What's new after each update.** To read it again, select **Settings → About → What's new**.

### Fixes
- **Fixed sources that did not show.** Add-ons with an http:// address work. Some add-ons do not block Alder now. Dolby Vision sources show on all TVs.
- **Fixed skip forward.** Some movies, for example .ts files, do not start again from the beginning.
- **Fixed empty pages.** When an add-on gives no details, Alder uses the next add-on. All AIOMetadata catalogs show.
- **Fixed sign-in problems.** A profile stays after Stremio signs it out. Alder tells you when 2 profiles use the same account.
- **Fixed updates on Fire TV.** Alder does not stop during an update.
- **Fixed language problems.** Subtitles and audio tracks match in all add-ons.

## 1.1

- Each profile keeps its own streaming add-ons. Add-ons that you add or remove in Stremio show in Alder on the next page.
- Continue Watching shows correctly when Home opens for the first time.
- The **Audio & subtitles** panel in the player opens correctly.
- In **Search**, the selected poster is no longer cut off at the top.

## 1.0

- **Press Play and it plays.** Alder picks a good source and starts it. Hold OK on **Play** to see every source.
- **Automatic fallback.** When a stream fails, Alder tries the next one from the same second.
- **Only what your TV can play.** Alder checks your TV for 4K, HDR, Dolby Vision, HEVC, AV1 and DTS. It hides files that the TV cannot play.
- **Profiles.** Up to 5 profiles, each with its own Stremio account. A PIN is optional.
- **Stream preferences in plain words:** **Best picture**, **Balanced** or **Fast start**.
- **Series.** The next episode keeps the same release and quality. **Skip intro** is built in.
- **Continue Watching on the Google TV home screen.**
- **Frame-rate matching.** The TV changes to the film's frame rate.
- **Sync with Stremio.** Watch progress matches your phone and computer.
- **Updates.** Alder finds new versions on GitHub and installs them.
