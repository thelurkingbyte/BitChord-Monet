# BitChord-Monet — Progress Log

Living status for this BitChord fork. **Update after every phase/commit** so any agent can resume.

## Goal
Add to BitChord: (1) a TTML lyrics home-screen widget, (2) album-art wallpaper (home/lock), (3) system Monet colors derived from the album art. Maintain a rebase-able fork (never re-patch APKs) with a stable identity + signing key for seamless self-updates.

## Locked decisions
- **Packaging:** new `monet` flavor → applicationId `com.thelurkingbyte.bitchordmonet`, label "BitChord Monet". Coexists with official (`com.music.bitchord`) and `dev` (`com.dev.bitchord`).
- **Signing:** own keystore `~/.android/keys/bitchord-monet.jks` (alias `bitchord-monet`); passwords in gitignored `keystore.properties`. Debug = debug key; release = this key.
- **Git:** `main` = clean upstream mirror (never commit features there); work on `monet-features`; remotes `origin` (fork) + `upstream` (kushagrasinghx/BitChord).
- **Widget:** RemoteViews / AppWidgetProvider, matching the existing `app/.../widget/` package. No Glance.
- **Lyrics:** reuse the existing engine (Apple/TTML via am-lyrics, multi-provider). Line-level sync v1.
- **Wallpaper:** user-triggered `WallpaperManager` action (Home / Lock / Both). No auto-change.
- **System Monet:** not set directly (no public API). Android 12/13+ derives it from the wallpaper set in feature 2.
- **Out of scope:** watch / TV / backend. Build the phone with `-Pbitchord.bundleWear=false`.

## Environment (this machine)
- Ubuntu WSL2, 4 CPU, ~3.8 GB RAM + swap. Local builds are memory-tight — **prefer GitHub Actions**.
- JDK 17: `/usr/lib/jvm/java-17-openjdk-amd64`.
- Android SDK: `/home/user/Android/Sdk` (set in `local.properties`).
- **Local-only, NOT committed:** `gradle.properties` heap lowered to `-Xmx2048m`, `org.gradle.workers.max=2`, `kotlin.daemon.jvmargs=-Xmx1024m` to avoid OOM. CI uses the committed values.
- **SDK alias:** AGP 8.10.1 resolves `compileSdk 37` to hash `android-37`, but the SDK ships it as `android-37.0`. Local/CI fix: `cp -a $ANDROID_HOME/platforms/android-37.0 $ANDROID_HOME/platforms/android-37` and set `AndroidVersion.ApiLevel=37` in its `source.properties`. CI does this in a workflow step.

## Build
- Local (memory permitting): `./gradlew :app:assembleMonetDebug -Pbitchord.bundleWear=false`
- CI: `.github/workflows/build.yml` builds the same on GitHub runners and uploads the `BitChord-Monet-debug` artifact.
- Signed release: `./gradlew :app:assembleMonetRelease -Pbitchord.bundleWear=false` (requires `keystore.properties`).

## Release signing (seamless updates)
Debug APKs from CI are signed with an ephemeral per-run debug key, so one cannot update another (Android rejects the signature mismatch). For **seamless self-updates that keep app data**, build the release variant signed with your own stable key:
1. Add these repository secrets (GitHub → Settings → Secrets and variables → Actions):
   - `MONET_KEYSTORE_BASE64` — `base64 -w0 ~/.android/keys/bitchord-monet.jks`
   - `MONET_STORE_PASSWORD`, `MONET_KEY_ALIAS`, `MONET_KEY_PASSWORD` — from `keystore.properties`
2. The workflow then also builds a signed `BitChord-Monet-release` artifact. Install that once; every later release updates it in place and keeps its data.
Without the secrets, only the debug APK is produced — fine for testing, not for seamless updates.

## Status
- [x] **Phase 0 — baseline:** toolchain installed (JDK 17, Android SDK 37), fork cloned, `upstream` wired, platform 37 installed + aliased.
- [x] **Phase 1 — identity:** `monet` flavor (`app/build.gradle.kts`), keystore + `keystore.properties`, CI workflow.
- [x] **Phase 2 — wallpaper:** `SET_WALLPAPER` permission + user-triggered "Set album art as wallpaper" (Home/Lock) from the song menu (`wallpaper/WallpaperArt.kt`).
- [x] **Phase 3 — Monet toggle:** opt-in "Auto wallpaper" (Appearance settings) applies the current track's art as wallpaper on change — Android 13+ derives Monet from that wallpaper.
- [x] **Phase 4 — lyrics widget:** `widget/LyricsWidget.kt` + snapshot + layout + manifest receiver; fed by `PlaybackService.publishLyricsWidget()` on lyric-line changes (line-level, prev/current/next).
- [x] **Phase 5 — polish:** CI workflow + `MONET.md`/`AGENTS.md` (done).

## Next action
All three features build **green** on GitHub Actions (run on commit `4d0b44fa`). To install: download the `BitChord-Monet-debug` (or `-release`, once secrets are set) artifact from the Actions run and `adb install -r` it. Then test: song-menu "Set album art as wallpaper", Appearance → "Auto wallpaper", and the home-screen lyrics widget.

## Resume checklist
1. `cd` to the repo and `git checkout monet-features`.
2. Export `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` and `ANDROID_HOME=/home/user/Android/Sdk`.
3. Build via CI (push) or locally with `./gradlew :app:assembleMonetDebug -Pbitchord.bundleWear=false`.
4. Read this file's Status + Next action before doing anything.