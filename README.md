# Family DNS Lock (Android)

Locks Private DNS on your Samsung S23 Ultra to `family.cloudflare-dns.com`
and prevents it from being changed in Settings.

## Why this needs two layers

Android deliberately gives normal apps **no API at all** to change Private
DNS or to close another app's screen — otherwise any app on the Play Store
could hijack your traffic. To get real enforcement you need elevated status:

1. **Device Owner (the real lock).** `DevicePolicyManager.setGlobalPrivateDns()`
   is only callable by a Device Owner app. Once set, Android's Settings UI
   shows Private DNS as **managed by your admin** and greys out the field —
   the user physically cannot edit it, no "kick them out" trick required.
2. **Accessibility Service (a backup layer).** Watches for the DNS screen
   opening and immediately backs out, as defense-in-depth in case the lock
   is toggled off, or during the moment before it's re-applied.

Layer 1 does the actual locking. Layer 2 is a convenience net on top of it —
by itself, without Device Owner, it cannot set DNS.

## Build

Open this folder in Android Studio (Jellyfish or newer) and let it sync
Gradle, or build from the command line if you have the Android SDK/Gradle
wrapper set up:

```
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`. Install it
with `adb install app-debug.apk`.

## Provisioning as Device Owner (one-time, via a PC)

Device Owner provisioning **only works on a device with no user/Google
accounts added** (i.e. right after a factory reset) — this is an Android
security requirement, not something this app can work around.

Options for your S23 Ultra:

- **Easiest:** factory reset the phone, skip adding any account during
  setup, enable Developer Options + USB debugging, connect to a PC, install
  the APK, then run:
  ```
  adb shell dpm set-device-owner com.dnslock.family/.DnsDeviceAdminReceiver
  ```
  *Then* sign back into your Google/Samsung accounts — Device Owner status
  persists after accounts are added later.

- **If you don't want to factory reset:** remove all accounts under
  Settings → Accounts first, then run the same command. This works on
  stock/AOSP-like builds; some Samsung Knox firmware restricts `adb shell dpm
  set-device-owner` outright depending on region/carrier. If the command
  fails with a Knox-related error, a factory reset is the reliable path.

Once provisioning succeeds, open the app — it should say "Device Owner:
ACTIVE" and the toggle will work.

## Enabling the Accessibility Service (one-time, on-device)

Tap "Open Accessibility Settings" in the app, find **Family DNS Lock**, and
turn it on. This step is manual by Android design — no app, including this
one, can silently enable its own accessibility permissions.

## Using it

Flip the switch in the app. It calls `setGlobalPrivateDns()` with
`family.cloudflare-dns.com` in hostname mode. To verify: Settings → Connections
→ More connection settings → Private DNS should now show the value, disabled
for editing.

Turning the switch off calls `setGlobalPrivateDns()` again in opportunistic
mode, releasing the lock.

## Known limitations

- **One UI class names.** The accessibility heuristic in
  `DnsLockAccessibilityService.kt` matches on both class-name hints and
  visible "Private DNS" text, which should cover most One UI versions.
  If it doesn't fire on your exact firmware, connect via `adb` while the
  DNS screen is open and run:
  ```
  adb shell dumpsys window windows | grep mCurrentFocus
  ```
  then add whatever class name you see to `dnsClassHints` in that file.
- **Not Play Store distributable as-is.** Device Owner apps and
  accessibility services with this kind of behavior fall under Google
  Play's restricted-permissions and MDM policies. This is fine for personal
  sideloading (which is what `adb install` does) but would need an
  Enterprise Mobility Management declaration to publish.
- **Removing Device Owner** later requires either
  `adb shell dpm remove-active-admin com.dnslock.family/.DnsDeviceAdminReceiver`
  (if allowed) or another factory reset — there's no in-app "self-destruct"
  by design, since that's exactly the kind of bypass this app exists to
  prevent.

## A quick note on scope

This only makes sense to run on a device you own and control, or a family
member's device with their knowledge (e.g., a child's phone) — the same way
any parental-control or self-control tool should be used transparently.
