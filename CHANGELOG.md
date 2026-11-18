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
- **Arabic interface.** Select **Settings → Language → App language → العربية**. Alder shows menus, buttons and settings in Arabic.
<!-- Spotlight -->
- **P2P sources.** Alder can play torrents from your add-ons without a debrid account. Sources with the **P2P** label are at the end of the source list. When no other source plays, Alder tries a P2P source. Seek, pause and Back work as with other sources. P2P sources are available on Android 7 and later.
<!-- Spotlight -->
- **Bigger text and posters.** Select **Settings → Picture & Sound → Text and poster size → Large**. Text, posters, buttons and panels get bigger on every screen. Rows show fewer posters, and Home shows the story only on the title page.
- **Play in another app.** Select **Settings → Streams → Video player**, then select a video app on your TV, for example VLC or Just Player. Alder opens that app when you select **Play**. Alder still selects the source, the resume point and the subtitle. When you press Back in the other app, Alder saves where you stopped. If you remove that app, Android asks which app to use.
<!-- Spotlight -->
- **Anime and other catalogs.** Catalogs of anime, video channels and other types now show. Each type has its own page in the top bar, for example **Anime**. Before, Alder showed only movie, series and live catalogs. Anime with episodes has its episode list, **Next episode** and **Continue**, as a show does. **My List** shows these titles too.
<!-- Spotlight -->
- **Picture size.** You can make the picture fill the screen. In the player, select **Audio & subtitles → Sync & Style → Picture**. **Fit** shows all of the picture. **Zoom** fills the screen and cuts the edges. **Stretch** fills the screen and changes the shape. Alder keeps your choice for all videos.
<!-- Spotlight -->
- **Letterboxd ratings.** Alder can show the Letterboxd rating of a film next to the IMDb rating, or in its place. Select **Settings → Profile → Ratings**. Letterboxd ratings need a free MDBList key. Select **MDBList key** and scan the code with your phone, then paste the key on the phone. Shows always use IMDb.
- **Alder website.** The House Lights website shows the TV app, setup steps, and a link to the latest APK.
- Add an Alder website card image with the app screen and product text.
<!-- Spotlight -->
- **Send report.** When Alder stops, it saves the error on your TV. The next time Home opens, Alder asks to send a report. Select **Send report** or **No**. The report has the error, the Alder version and the TV model. It does not have your account details. To send it later, select **Settings → About → Send report**.

### Easier to use
- **Choose your tabs.** Select **Settings → Profile → Tabs**. Select a tab to show it, hide it or move it left or right. This includes the tabs of add-ons, for example **Trakt**. A hidden tab keeps its add-on installed. **Home** is always the first tab. Each profile has its own tabs. **Tabs** replaces the **Movies tab**, **Shows tab** and **Live tab** settings and keeps your choices.
- **Faster Home.** Alder shows saved rows while it gets new data. When add-ons change, focus returns to the same title if it is still available.
- **My List.** Two full rows of posters show at once. Move down to see more titles. The title shows while Alder gets the poster.
- The filters, sort menu and title count show above the posters.
- **My List art.** A dark picture of the selected title shows behind the posters.
- Empty poster slots have a dark background while Alder gets the images.
- Alder asks for confirmation before it removes a title from **My List**.
- **Movies.** Select a genre in **Jump to** to go to its shelf. Select **Top** to go to the first shelf.
- **Movies posters.** A short line marks the selected poster. Alder shows the title while it gets the poster.
- **Shows.** Select a genre in **Jump to** to go to its shelf. Select **Top** to go to the first shelf.
- **Shows posters.** The posters are brighter. A short line marks the selected poster. Alder shows the title while it gets the poster.
- **Continue Watching.** The shelf shows the time left and a progress bar below the selected poster.
- **Home.** A short line marks the selected poster. Shelf names are larger. Posters fade at the right edge until you reach the last title.
- The top bar and story text are easier to read over bright art.
- **Source list as your add-on writes it.** Select **Settings → Streams → Source list → As the add-on writes it**. The source list gets wider and shows the text of each add-on, as Stremio does. If you set a format in AIOStreams, you see that format. **Clean** keeps the short rows of Alder.
- **More facts in the source list.** The source list shows the size of more sources, the audio as format and channels (for example **DD+ 5.1**), and the number of seeders for P2P sources (**👤 52**). **⏳** after a debrid service shows that the service must download the source before it plays. Below the list, Alder shows the full text that the add-on wrote for the selected source.
- **Change stream keeps your video.** When you select **Change stream**, the video stops and stays behind the source list. Press Back to continue from the same picture without a new load. Select a different source to play it from the same second.
<!-- Spotlight -->
- **Smooth motion.** Screens fade and settle when they open and close. When you select **Play**, the screen dims to black and the video fades up. A title page opens with its picture moving slowly into place. Focus settles with a small bounce. Panels open fast and close faster. To turn off motion, set the TV to **Remove animations** in its accessibility settings.
- **Gray subtitles.** You can show subtitles in gray. Gray is less bright than white on OLED and Mini LED TVs in a dark room. Select **Settings → Subtitles → Subtitle color → Gray**, or select **Gray** in the player.
- **Subtitle match.** Alder sends the file name and size of the video to subtitle add-ons. The add-on shows subtitles for that release first, so the timing is correct more frequently.
- **Alder website.** The site shows the app screens and makes the download and setup steps easy to find.
- The README has a **Website** badge and a **Downloads** badge with the total number of GitHub release downloads.

### Fixes
- **Picture and sound labels in the player.** A wide 4K film now shows **4K**. Before, it showed **1440p**, and a wide 1080p film showed **720p**. When you select a different audio track, the labels show that track. Before, an AAC track could show **ATMOS** from the file name. DTS:X files now show **DTS X**.
- **Watch progress.** Alder keeps your place on the TV if Stremio does not save it. Alder sends it again when Home opens or during playback.
- **My List and watched marks.** Alder shows the change after Stremio saves it. If the save fails, select **Try again**.
- **Error messages.** Alder shows the cause when it can find it. Home and Search show a message if some results are not available.
- **Crash reports.** Reports include recent error types and the result of each retry that Alder records. The new data has no links or account details.
- **P2P episodes.** Alder checks the file names in a torrent when the add-on gives no file index. Alder skips a pack if it cannot find one file for the selected episode.
- **P2P links.** Alder can play sources from add-ons that use `magnet:` or `torrent://` links.
- **Top bar tabs.** Add-on tab names align with the other tabs and use the correct color as soon as they show. **Search** uses your tab choices. You can change the **Shows**, **Movies** and **My List** tabs when Alder cannot get the add-on list. **Tabs** shows changes to your add-ons.
- **Lossless audio.** Alder sends TrueHD and DTS-HD to a compatible receiver in **Auto**. If passthrough fails, Alder tries the same track as PCM.
- **Movies art.** The title and art change together when you stop on a poster.
- **Shows art.** The title and art change together when you stop on a poster.
- **Home art.** The title and art change together when you stop on a poster. Pictures that are ready fade directly into each other. Alder shows episode numbers when they are available.
- **Resume.** **Continue Watching** and the title page use the same resume point. The title page shows where you stopped.
- **4K source selection.** If Alder selects 1080p on a 4K TV, select **4K** under **Picture & Sound → Display resolution**. With **Best picture**, Alder gives 4K priority when the other preferences are equal.
- The caption moves with the selected card when you press Left or Right.
- **Profile PIN.** Another app cannot open a profile that has a PIN. Alder asks for the PIN first.
- **Sign-in and backups.** Android does not copy profiles, sign-ins and PINs to a new TV.
- **Watch progress.** **My List** and the watched marks do not erase your progress from other devices. Alder gets your Stremio library first, then saves the change.
- **Sound after you leave.** A video does not start again when you go to another app while Alder gets a subtitle or a source. When you come back, the video starts. Before, Alder sometimes went to the next source.
- **Resume point.** Alder goes to the resume point from your other devices also when a subtitle or the file is slow to start.
- **Start again from the beginning.** Left and Right during playback do not start some videos again from the beginning. Before, a change to the subtitle timing in a file that cannot skip ahead started the video at 0:00.
- **Add-on subtitles.** Subtitles from SubDL, OpenSubtitles and other add-ons show correctly. Alder opens zip files and shows letters in all languages correctly, for example Arabic, Polish and Russian. Each subtitle add-on shows its own files in **Audio & subtitles**. Before, files from the first add-on hid the files from other add-ons.
- **Missing sources.** Alder asks every stream add-on that Stremio asks. Before, Alder did not ask some add-ons, and showed no playable sources. Short videos, for example a 7-minute cartoon, show in the source list. Slow debrid add-ons get 45 seconds to answer, not 20.
<!-- Spotlight -->
- **Large files.** Large files, such as 4K remuxes, stop less often to load. On most TVs, Alder now keeps up to 200 MB of the video ready. Before, Alder kept approximately 131 MB, which is only 10 to 17 seconds of a remux. After a stop, Alder waits for 4 seconds of video before it starts again.
- **Long rows.** Alder releases poster memory for titles far off the screen. Rows stay fast when you move through many titles.
- **Rows.** Each row on Shows and Movies keeps its own place. Before, a move to the next row went to the same position as in the last row.
- **Smooth row motion.** Rows move smoothly when you move right fast. Live rows move at the same speed as poster rows. Background art waits until you stop on a title.
- **Missing sources.** The source list tells you which add-ons gave no sources, and why. Before, Alder told you only when no source was available. An add-on that finds only torrents needs a debrid service.
- **Backups.** Alder does not put profiles, sign-ins, or PINs in Android backups or device transfers. After a restore to a new TV, sign in to each profile again.
- **Android 6 and 7.** Alder starts on Android 6 and 7 TVs. Before, Alder closed and the TV showed the home screen.
- **Start after a downgrade.** Alder starts when you install an older version over a newer one. Before, Alder closed at start and the TV showed the home screen.

## 1.3.1

### Fixes
- **Alder.apk download.** The release file uses the Alder.apk name.

## 1.3

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
- **House Lights logos.** Alder uses the tungsten mark on the TV banner, transparent app icon, and small transparent logo. The profile picker and Settings headers use vector art.
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
- **Live subtitle sync.** Adjust subtitle delay with Left and Right while the video plays. This works for embedded subtitles and add-on subtitles.
- **Close player controls.** Press Back to close the controls while the video plays. Press Back again to leave the player.
- **Exact subtitle remembered.** When you play a video again, Alder uses the exact subtitle track that you selected before.
- **Update notification dot.** A dot on your profile avatar shows when a new version of Alder is ready.
- **Install an update from the profile menu.** Select your profile avatar, then select **Install update**. Alder installs version 1.3 over version 1.2.
- **Faster update checks.** Alder checks for new releases every 4 hours instead of once a day.

### Fixes
- **Subtitle language list in Audio & subtitles.** All add-on subtitle languages appear under Subtitles. Quick sync sits below the list so remote focus moves smoothly.
- **Up next episode card.** When credit times are available, Alder shows Up next only after the credits start. The fallback starts in the final 15 to 25 seconds.
- **Skip intro button.** Alder reads MKV intro chapters first. Alder filters IntroDB segments with low confidence and stops false prompts at the start of a video.
- **Remote focus and scroll in Audio & subtitles.** When you select a track, remote focus stays on it and the list keeps its position. The list moves smoothly and stays in its panel.
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
