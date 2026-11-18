# BitChord-Monet — Agent Guide

## What this repo is
A fork of [kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord) (Kotlin Multiplatform, GPL-3.0) adding:
1. A TTML lyrics home-screen widget.
2. Album-art wallpaper (home / lock).
3. Wallpaper-driven system Monet colors.

See `MONET.md` for current status and the next action.

## Branches
- `main` — clean upstream mirror. **Never commit features here.**
- `monet-features` — all mod work. Rebase onto `main` after upstream updates.

## Build (phone)
Always pass `-Pbitchord.bundleWear=false` (watch/TV bundling is out of scope for the `monet` flavor).

```bash
# Debug
./gradlew :app:assembleMonetDebug -Pbitchord.bundleWear=false
# Signed release (needs keystore.properties)
./gradlew :app:assembleMonetRelease -Pbitchord.bundleWear=false
```
APK: `app/build/outputs/apk/monet/debug/*.apk` (or `monet/release`).

## Seamless updates / release signing
Debug APKs are signed with an ephemeral debug key (cannot update each other). For self-updates that keep data, add repo secrets `MONET_KEYSTORE_BASE64`, `MONET_STORE_PASSWORD`, `MONET_KEY_ALIAS`, `MONET_KEY_PASSWORD`; CI then also emits a signed `BitChord-Monet-release` artifact. See `MONET.md`.

## Variants
| Flavor | applicationId            | Label           |
|--------|--------------------------|-----------------|
| dev    | com.dev.bitchord         | BitChord Dev    |
| prod   | com.music.bitchord       | BitChord        |
| monet  | com.thelurkingbyte.bitchordmonet | BitChord Monet |

## Toolchain
- JDK 17, Android SDK with platform 37 / build-tools 37.
- AGP 8.10.1 expects platform hash `android-37`; the SDK ships `android-37.0` → alias it (see `MONET.md` and the CI workflow).

## Rules
- **Never commit secrets:** `keystore.properties`, `*.jks`, `local.properties`, passwords, tokens.
- Keep changes rebase-friendly: small, feature-scoped commits with `feat:` messages.
- Reuse existing infrastructure:
  - Widgets: `app/src/main/java/com/music/bitchord/widget/` (RemoteViews/AppWidgetProvider, `MediaWidgetSnapshot`, `MediaWidgetActions`).
  - Theming: `androidx.palette` + `rememberArtworkColors`/`MeshPalette` (already artwork-driven).
  - Lyrics: existing engine (Apple/TTML via am-lyrics, multi-provider).
  - Images: Coil 3.
  - Playback state: `PlaybackService`, `PlayerConnection`.
- Update `MONET.md` status after each phase.