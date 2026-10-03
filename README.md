# Automatic Call Recorder

Android-first Flutter prototype for automatically detecting cellular call state, starting/stopping a native Android recorder, storing call metadata locally, and showing saved recordings in a Flutter history screen.

## Current behavior

- Requests microphone, phone-state, call-log and notification permissions.
- Starts a foreground Android service while the app is armed.
- Detects incoming/outgoing call state.
- Starts a local `.m4a` recording when the phone enters the off-hook state.
- Stops and saves the file when the call returns to idle.
- Stores phone number, call direction, timestamps, duration, file path and basic audio-detection metadata.
- Plays recordings through the normal Android media output with play/pause support.
- Keeps the service running when the Flutter activity is swiped away, subject to Android/OEM background-process rules.

## Important Android limitation

This project is a normal third-party Android app. Modern Android does not provide ordinary apps unrestricted access to the cellular call uplink/downlink audio stream. The app therefore records from the microphone source that Android exposes to it. On some phones Android/OEM audio policy can silence that source while a cellular call owns the microphone. When no microphone amplitude is detected, the UI marks the saved item accordingly instead of pretending that audible call audio was captured.

A privileged/system app, OEM-integrated recorder, rooted-device solution, or a calling stack where the app controls the VoIP media stream is required for reliable two-sided call audio on devices that block third-party cellular call capture.

## Run

```bash
flutter clean
flutter pub get
flutter run
```

## Build APK

```bash
flutter build apk --release
```

APK output:

```text
build/app/outputs/flutter-apk/app-release.apk
```

## Android files

The native implementation is primarily in:

```text
android/app/src/main/kotlin/com/example/call_recorder/MainActivity.kt
android/app/src/main/AndroidManifest.xml
```

The Flutter UI is in:

```text
lib/main.dart
```
