# BD World Live TV
Android Kotlin starter project with Media3 player and GitHub Actions.

Defaults: Dark theme, 720p target preference, automatic selection ON, scheduled scan every 15 minutes.

## Build APK
1. Extract this ZIP.
2. Upload all files to a new GitHub repository on branch `main`.
3. Open GitHub > Actions and run `Build APK and refresh playlist`.
4. When it finishes, download the `BD-World-Live-TV-debug-apk` artifact.

## Important
This is source code, not a compiled APK. GitHub Actions builds the APK.
The scanner checks HTTP reachability only; it cannot guarantee playback, stable service, or 720p. The Android player reports playback errors when a channel is attempted. GitHub scheduled jobs may be delayed. Use only streams you are authorized to access.
