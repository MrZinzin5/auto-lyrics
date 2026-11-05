# Auto Lyrics — GLES fork

A fork of [efrayim-dev/auto-lyrics](https://github.com/efrayim-dev/auto-lyrics) that rewrites the Android Auto side with a custom OpenGL ES renderer.

Why: Google's Car App host rate-limits non-navigation templates (`PaneTemplate`, `ListTemplate`, …) to one refresh every ~5 seconds. On songs with short lyric lines that cap causes visible line skips. `NavigationTemplate` is exempt from the quota but doesn't natively render content past a small top-left bubble — the real escape hatch is the `SurfaceCallback` it exposes, which lets an app draw whatever it wants on its own.

This fork takes that path: NavigationTemplate + an EGL/GLES 2 pipeline that draws a full-screen lyrics view at ~100 fps, independently of the host's refresh quota.

## Credits

All the backend plumbing — media session capture, LRCLIB client, LRC parser, local cache, phone companion UI, ML Kit translation — is from the upstream project by [@efrayim-dev](https://github.com/efrayim-dev). Full credit goes to them for making this project work at all. If you like this fork, please star the upstream too.

What this fork adds:

- `app/src/main/java/com/autolyrics/auto/LyricsGlRenderer.kt` — EGL14 + OpenGL ES 2 renderer: shader-based textured quad, cached blurred-album background, SoundCloud-style waveform, rolling three-line lyrics.
- `app/src/main/java/com/autolyrics/auto/LyricsScreen.kt` — NavigationTemplate shell driving the renderer at ~100 fps.
- `app/src/main/java/com/autolyrics/auto/LyricsSession.kt` + `LyricsCarAppService.kt` — Car App service plumbing.
- New launcher icon and bumped to `v2.0.0`.

## Features

What you see on the Android Auto screen:

- **3 lines of lyrics, centered** — previous, current (bold + shadow), next, with the current one tracked continuously in time.
- **Rolling scroll** — lines slide into place during the last 30% of each line's duration; your eye never has to chase a jump.
- **~100 fps refresh** — bypasses the ~5s template quota via `NavigationTemplate` + a custom GLES draw loop.
- **Blurred album art background** — iterated downscale/upscale chain (no RenderScript), cached per album, with a dark overlay and dominant-color vignette.
- **Header strip** — rounded cover, title, artist, and a SoundCloud-style waveform whose bar heights are deterministic per track and whose "played" portion fades smoothly bar-by-bar instead of snapping.
- **Phone companion UI unchanged** — the original phone activity still works, this fork only touches the car side.

And from the upstream project (unchanged by this fork):

- Works with any music player that exposes a MediaSession (Spotify, YouTube Music, Deezer, Poweramp, Apple Music, …)
- Synced lyrics from [LRCLIB](https://lrclib.net/)
- Automatic song detection via media session APIs
- Multi-strategy lyrics lookup (exact match, keyword search, fuzzy fallback)

## Architecture

```
MediaListenerService (NotificationListenerService)         [upstream]
    │
    ▼
MediaTracker (singleton, StateFlow)                        [upstream]
    │   ├── detects song changes → LrcLibClient → LrcParser
    │   └── tracks playback position → updates current lyric index
    │
    ├─────────────────────────┐
    ▼                         ▼
LyricsScreen                  MainActivity
(NavigationTemplate)          (Phone UI — upstream)
    │
    ▼
LyricsGlRenderer              [this fork]
(EGL + GLES2 on the host surface)
```

## Building

JDK 17, Android SDK 34, Gradle via the committed wrapper. On Windows:

```powershell
$env:JAVA_HOME = "<your jdk17 path>"
.\gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Installing on a phone

1. Enable Developer options → USB debugging → Install via USB (on Xiaomi/HyperOS, also disable MIUI Optimization or the app will be killed in the background).
2. `adb install -r app-debug.apk`
3. Open the app once, grant it **Notification Listener** access when prompted. Without that permission it can't see what Spotify / Deezer / etc. are playing.

## Enabling it in Android Auto

Android Auto only shows apps signed by registered developers out of the box. To sideload this one:

1. Open **Android Auto** on the phone → Settings → scroll to **Version** → tap it ~10 times to unlock Developer settings.
2. Open **Developer settings** → enable **Unknown sources**.
3. (If the toggle sticks on "off", toggle it a couple of times and reboot the phone — Google occasionally resets this flag.)

## Caveats

- **Debug signing key is in the repo.** `app/signing.p12` is a shared debug key committed to the upstream repo. That's fine for sideloading — anyone who builds this project ends up with APKs signed by the same key — but it means this fork is **not Play-Store-publishable as-is**. Don't upload the debug-signed APKs to download sites either: you'd be distributing something signed by a key you don't actually control.
- **No guarantees about head-unit compatibility.** This has been verified on the DHU emulator + a Xiaomi 17 phone. Other head units (notably older ones) may have different Surface setups that behave differently with the EGL path.
- **NavigationTemplate requires declaring NAVIGATION_TEMPLATES + ACCESS_SURFACE** in the manifest. The app does so and calls `NavigationManager.navigationStarted()` + a dummy `updateTrip(Loading)` on resume — this is the host's precondition for handing out the surface.

## License

The upstream repo (`efrayim-dev/auto-lyrics`) does not declare a license. This fork is distributed on the same implicit terms: fork it, build it, run it, learn from it. **Please don't repackage it under your own name**, and if you want to redistribute it in a hardened form (Play Store, APK mirror), please open an issue on the upstream first to ask about licensing.
