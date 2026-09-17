# Tiny Alarm

A tiny Android alarm app with no ads, no tracking, and up to five alarms you can add or delete.

## Build

Open this folder in Android Studio:

`/Users/jack/Projects/TinyAlarm`

Or build from Terminal:

```sh
./gradlew assembleDebug
```

The debug APK is created at:

`app/build/outputs/apk/debug/app-debug.apk`

## Phone Setup

On first launch, allow notifications if Android asks.

On Android 12 or newer, tap **Allow exact alarms** in the app if shown. This is needed so alarms ring at the precise minute even when the phone is idle.

For the most reliable wake-up behavior on Xperia:

- Keep Tiny Alarm out of battery optimization if Sony's settings offer that option.
- Make sure the system alarm volume is high.
- Test one alarm a few minutes ahead before relying on it overnight.
