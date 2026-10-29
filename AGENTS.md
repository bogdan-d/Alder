# Agent notes

This repo is a personal fork (`origin`) of [aradhyas8/Alder](https://github.com/aradhyas8/Alder) (`upstream`), an Android TV app. Read `README.md` for what the app does. Machine and device details (SDK location, signing key, TV addresses) are in the agent's project memory, not here.

## Keeping the fork mergeable

Upstream is merged into `main` regularly, so every fork change is a future merge conflict. Keep the diff from upstream as small as possible: one-line changes beat refactors and removals, and new files beat edits to upstream files. If upstream already fixes something, merge it instead of fixing it here.

Fork-only changes so far:

- `Updates.kt`: `LATEST` points at this fork's releases. Merges from upstream must keep this line.
- `AGENTS.md`: this file.
- Player fixes, offered upstream in [aradhyas8/Alder#40](https://github.com/aradhyas8/Alder/pull/40) (branch `player-fixes`): the exact subtitle remembered per video (`PlayerActivity.kt`, `SettingsActivity.kt`), Back closing the controls first, and `android:enableOnBackInvokedCallback="false"` so Android 16 sends Back as a key press (`AndroidManifest.xml`). Once upstream has them, a merge brings them back as upstream code; remove them from this list.

To sync: `git fetch upstream && git merge upstream/main`. Then build, and check that the `LATEST` line still points at the fork.

## Build

- Run Gradle as `sh ./gradlew …`. Upstream commits `gradlew` without the executable bit; keep it that way so it doesn't show up as a fork change.
- `sh ./gradlew assembleRelease test` builds `app/build/outputs/apk/release/app-release.apk` and runs the unit tests.
- Media3 is pinned to the version of Jellyfin's ffmpeg decoder (`app/build.gradle.kts`). Bump both together or neither.

## Signing

Release builds are signed with the key named in `keystore.properties` (git-ignored, repo root). The TVs run builds signed with that key, so:

- Install only release builds on them. A build signed with another key (any debug build) can only be installed by uninstalling Alder first, which deletes its profiles.
- Never commit, copy or print the keystore or `keystore.properties`.

## Installing on a TV

The TVs are reached over network adb on port 5555.

```sh
adb connect <tv>:5555
adb -s <tv>:5555 install -r app/build/outputs/apk/release/app-release.apk
adb -s <tv>:5555 shell monkey -p dev.alder -c android.intent.category.LEANBACK_LAUNCHER 1
```

A TV in standby shows as `offline` in `adb devices`, and stays that way after it's turned on. Ask the user to turn it on, then run `adb kill-server` and `adb connect` again.

## Releasing an update

The TVs check this fork's latest GitHub release once a day and offer to install it.

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`, and add notes under `## <versionName>` in `CHANGELOG.md`, following the writing rules at the top of that file. The app shows these notes in "What's new". Upstream adds its own sections at the same place, so expect a small conflict there on the next merge.
2. `sh ./gradlew assembleRelease`
3. Create a GitHub release on the fork tagged `v<versionName>` (for example `v1.3`) with the APK attached. The app installs the first `.apk` asset it finds.

Publishing a release reaches every TV, so confirm with the user before creating one. Upstream bumps its version too; if a merge from upstream raises `versionName`, the next fork release must be at least as high.
