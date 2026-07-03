# Family DNS Lock (Android)

Read-only checker for the device's Private DNS setting. Shows whether
`family.cloudflare-dns.com` is configured as the Private DNS hostname.

## How it works

The app reads `Settings.Global.PRIVATE_DNS_MODE` and
`Settings.Global.PRIVATE_DNS_SPECIFIER` (available from Android 9 / API 28).
It does **not** change DNS, block settings screens, or require special
permissions.

- Green ring around **Is DNS set?** — mode is `hostname` and the host matches
  `family.cloudflare-dns.com`
- Gray ring — anything else (off, automatic, or a different hostname)

## Build

Open this folder in Android Studio (Jellyfish or newer) and let it sync
Gradle, or build from the command line if you have the Android SDK/Gradle
wrapper set up:

```
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`. Install it
with `adb install app-debug.apk`.

## Using it

Set Private DNS manually in **Settings → Connections → More connection
settings → Private DNS** to **Private DNS provider hostname** and enter
`family.cloudflare-dns.com`. Open this app to confirm the setting — the
status refreshes whenever you return to the app.

## Known limitations

- **Read-only.** This app cannot set or enforce Private DNS; it only reports
  the current system value.
- **Manual setup.** The user must configure Private DNS in system Settings.
