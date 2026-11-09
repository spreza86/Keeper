# Keeper

Personal Android app (Kotlin + Jetpack Compose) for Saul: suggests Sony RAW photos to delete and groups the rest by trip, place, colour and light. See README.md for how it decides.

## Rules
- Fully offline: the manifest removes INTERNET and ACCESS_NETWORK_STATE. Never add network access.
- Never use em dashes in UI text or written suggestions.
- Each version: bump versionCode/versionName in app/build.gradle.kts, add a "What's new" section to README.md, build, send the APK in chat, commit and push to main with a message starting "Keeper X.Y:". Update the project doc claude/keeper-status.md (decisions, open items).

## Build (cloud session)
```
export ANDROID_HOME=/opt/android-sdk
echo "sdk.dir=/opt/android-sdk" > local.properties
./gradlew lintDebug assembleDebug --console=plain
```
APK: app/build/outputs/apk/debug/app-debug.apk. Signed with app/keeper-debug.keystore (in the repo, since 0.6). Never change or regenerate it: a different key makes Android refuse to update ("App not installed").

A fresh session has no Android SDK: get commandlinetools from dl.google.com into /opt/android-sdk/cmdline-tools/latest, accept licenses, install platforms;android-35, build-tools;35.0.0, platform-tools. repo1.maven.org returns 429 under parallel downloads: use --max-workers=1 and retry. settings.gradle.kts only uses google() and the repo1.maven.org mirror (mavenCentral/gradlePluginPortal get 429/403 through the proxy). No emulator (no KVM): Saul tests on his phone.
