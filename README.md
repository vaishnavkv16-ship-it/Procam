# ProCam

Camera2 pro camera for Android (built for realme 15 Pro, works on any Android 9+ phone).

## Features
**Photo:** manual ISO, shutter, focus, white balance (presets + Kelvin), EV, zoom, lens picker (logical + physical cameras), JPEG at 100% quality at any size, RAW/DNG, flash, timer, OIS, noise reduction and sharpening modes, grid, tap-to-focus.

**Video:** HEVC or H.264, 20-150 Mbps bitrate, 8K/4K/1440p/1080p/720p, 24/30/60/120 fps (only what your phone exposes), OIS / EIS / preview stabilization, manual ISO and shutter, torch, 256 kbps AAC audio.

Tap INFO in the app to see what your phone actually exposes.

## Build with GitHub (no Android Studio needed)
```
git init && git add . && git commit -m "ProCam"
git branch -M main
git remote add origin https://github.com/<you>/ProCam.git
git push -u origin main
```
Then open the repo > Actions > "Build APK" > latest run > Artifacts > ProCam-apk. Unzip and install the APK.

If the build fails, open the failed step and send me the error text. Fixes are usually one line.

## Notes
- Photos save to Pictures/ProCam, videos to Movies/ProCam.
- 120 fps and 8K often need vendor high-speed modes that third-party apps cannot access.
- Some realme lenses and stabilization modes are hidden from third-party apps.
