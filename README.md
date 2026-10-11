# Photoliz

> Photoliz treats your phone as a weirdly designed plain digital camera, not a machine that decides how your pics look.

Photoliz is a camera app for Android and iOS that talks to the physical camera sensors directly. Every JPEG is
developed from the sensor's RAW data with as-shot white balance and the sensor's own color calibration — no tone
curve, no sharpening, no noise reduction, no "AI". The interface is a small set of old-digital-camera style
controls, and it only ever shows the controls your hardware really has.

| Platform | Supported versions | Output of the CI |
| --- | --- | --- |
| Android | Android 11 – 16 (API 30 – 36) | `Photoliz-<version>.apk` |
| iOS | iOS 13 – 27 (iPhone) | `Photoliz-<version>-xcode26-unsigned.ipa`, `Photoliz-<version>-xcode16-ios13-unsigned.ipa`, optional signed IPA |

## Features

**Both platforms**

- Uses the physical sensors one by one (never a fused "logical" / virtual multi-camera).
- Photo and video. Tap the shutter for one frame, hold it for a burst (optical stabilization is forced on during a
  burst). Volume buttons work as a shutter too (on iOS from iOS 17.2, including the Camera Control button).
- JPEG is always saved to the gallery, developed from RAW. DNG saving is optional (off by default).
- Aspect crops TRUE / 16:9 / 4:3, plus optional 2.39:1, 1.85:1, 2:3, 1.19:1 and 1:1. Crops apply to the
  viewfinder, the JPEG and the DNG default crop (the full sensor data stays in the DNG).
- Photo zoom is an honest crop of the sensor frame (up to 4×), shown exactly in the viewfinder.
- Autofocus only acts on your input: pressing the shutter focuses first and then shoots, tapping the viewfinder
  only focuses. AF can be switched off for manual focus.
- Focus and zoom sliders curved around the shutter (focus on, zoom off by default; both can be toggled in MENU).
- First run: a hardware report lists every control your phone supports and every control it does not.
- Four palettes: Canonical (default), Europe, Gaea, Island.

**Android (Camera2)**

- ISO (continuous, snapping to common values), shutter, aperture and ND filter dials. Each starts in auto
  (aperture at its widest); controls the sensor does not expose are hidden.
- Android 16 hybrid auto-exposure: on sensors that support it, a manual ISO with auto shutter (or the other way
  round) is done by the hardware. Elsewhere, setting one value freezes the other at its live value.
- Physical camera picker, including sensors that are only reachable through a logical camera, and "combo
  streams": two physical sensors of the same logical camera shooting at the same time (each verified by a
  one-time hardware probe).
- Sensor pixel mode button (binned / full resolution readout) on Android 12+ sensors that have it.
- Flash / torch / off, sensor test patterns, black level lock during bursts.
- RAW develop on the CPU: lens shading map, Malvar-He-Cutler demosaic, DNG color matrices interpolated for the
  scene illuminant, sRGB output.

**iOS (AVFoundation)**

- ISO and shutter dials (AVFoundation has no shutter- or ISO-priority mode: one manual value makes both manual;
  double tap a readout to return to auto).
- Physical camera picker (ultra wide, wide, tele, front) with a binned / full resolution toggle where the module
  has both.
- Flash / torch / off. Optical stabilization for photos (AVFoundation exposes it through one-frame brackets, which
  cannot fire the flash).
- Bayer RAW (not ProRAW) developed with Core Image's RAW filter with every enhancement switched off.

**Not available on iOS** (no API for it): adjustable aperture (iPhone lenses are fixed), ND filter, test
patterns, black level lock, simultaneous streams from two modules.

## Where files go

| | Android | iOS |
| --- | --- | --- |
| JPEG | `Pictures/Photoliz` | Photos (without Photos access: Files › On My iPhone › Photoliz › JPG) |
| DNG | `DCIM/Photoliz/DNG`, or a folder you pick in MENU › DNG FOLDER | Files › On My iPhone › Photoliz › DNG, or a folder you pick |
| Video | `Movies/Photoliz` | Photos (without Photos access: Files › Photoliz › Video) |

## Building

### With GitHub Actions

Push the repository to GitHub. `.github/workflows/build.yml` runs on every push and pull request:

| Job | Runner | Result (workflow artifacts) |
| --- | --- | --- |
| Android APK | `ubuntu-24.04`, JDK 17 | release APK + unit tests + lint report |
| iOS IPA (xcode26) | `macos-26`, Xcode 26 | unsigned IPA (current SDK), signed IPA when secrets are set |
| iOS IPA (xcode16-ios13) | `macos-15`, Xcode 16.4 | unsigned IPA; the build fails unless the binary targets iOS 13.0 |
| iOS IPA (xcode27, preview) | `xcode-27` | forward-compatibility build with the iOS 27 SDK; never blocks anything |

Pushing a tag such as `v1.2.0` also creates a GitHub release with the APK and IPAs attached (version name `1.2.0`,
build number = workflow run number).

**Signing secrets** (Settings › Secrets and variables › Actions). Everything is optional; without secrets you
still get installable builds.

| Secret | Purpose |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64` of your release keystore (`.jks`). Without it the APK is signed with the runner's debug key, so installing a build from a different run over an older one needs an uninstall first. |
| `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | Keystore credentials. |
| `IOS_CERTIFICATE_P12_BASE64`, `IOS_CERTIFICATE_PASSWORD` | `base64` of a signing certificate exported as `.p12`, and its password. |
| `IOS_PROVISIONING_PROFILE_BASE64` | `base64` of a provisioning profile for the bundle id `dev.panasko.photoliz`. |
| `IOS_TEAM_ID` | Optional, read from the profile when missing. |
| `IOS_CODE_SIGN_IDENTITY` | Optional, default `Apple Distribution`. |
| `IOS_EXPORT_METHOD` | Optional, default `release-testing` (ad hoc). Others: `app-store-connect`, `debugging`, `enterprise`. |

An unsigned IPA can be installed with a sideloading tool that re-signs it with your own Apple ID (for example
AltStore or Sideloadly), or re-signed with your own certificate.

### Locally

**Android** — JDK 17 and the Android SDK (platform 36):

```sh
cd android
./gradlew assembleRelease testDebugUnitTest
# app/build/outputs/apk/release/app-release.apk
```

Release signing uses the environment variables `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`; without them the debug key is used.

**iOS** — Xcode 16.4 or newer (Xcode 26 recommended for App Store builds; use Xcode 16.4 if you want the
toolchain that officially supports the iOS 13 deployment target):

1. Open `ios/Photoliz.xcodeproj`.
2. Select the Photoliz target › Signing & Capabilities and pick your team (change the bundle id if needed).
3. Run on an iPhone (the simulator has no camera).

Command line, unsigned:

```sh
cd ios
xcodebuild archive -project Photoliz.xcodeproj -scheme Photoliz -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' -archivePath build/Photoliz.xcarchive \
  CODE_SIGNING_ALLOWED=NO
```

The Xcode project is generated. After adding, moving or deleting files under `ios/Photoliz`, run
`python3 ios/tools/gen_xcodeproj.py` and commit the result (CI checks that the project is up to date).

## Repository layout

```
android/                 Gradle project (Kotlin, no third-party libraries)
  app/src/main/java/dev/panasko/photoliz/
    camera/              Camera2 engine, sensor catalog, hardware probe, exposure scales
    processing/          RAW develop, DNG crop patcher, saving (MediaStore / SAF)
    ui/                  Custom-drawn controls, sheets, viewfinder
  app/src/test/          JVM unit tests (exposure tables, color math, demosaic, DNG patcher)
ios/
  Photoliz/              Swift sources (App, Camera, Processing, UI, Util, Support, Resources)
  Photoliz.xcodeproj     Generated by ios/tools/gen_xcodeproj.py
design/render_icon.py    Renders the app icon (same geometry as the Android vector icon)
.github/                 CI workflow and the iOS archive action
```

## Notes

- Which controls appear depends on what the phone's camera HAL (Android) or AVFoundation (iOS) exposes for the
  selected sensor. The hardware report in MENU shows the details for your device.
- Long manual exposures: on Android the viewfinder simulates exposures longer than 1/15 s with sensor gain so it
  stays live; the photo uses the real exposure time.
- iOS may deliver a binned RAW frame even when the full resolution is selected (for example with Bayer RAW on
  quad-Bayer sensors); the JPEG and DNG always keep the frame that was actually captured.
- Android combo streams save one JPEG (and DNG) per sensor, suffixed with the module name.

## Credits

- **radacuxiv** — developer · [panasko.dev](https://panasko.dev)
- **Clemems Montiel** — photographer, consulting

Contact: [mail@panasko.dev](mailto:mail@panasko.dev)
