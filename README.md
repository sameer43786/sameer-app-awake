# Sameer App Awake

**Privacy-first, app-aware screen-awake control for Android 10+**

**Version 1.0.0**  
**By: Sameer Ali | Contact: sameer43786@gmail.com**

Sameer App Awake keeps the display awake only while user-selected applications are in the foreground. It is designed for dashboards, maps, PDF readers, monitoring tools, recipes, browser-based consoles, and other situations where Android's normal inactivity timeout interrupts a task.

> **Portfolio and licensing:** Source code is publicly available for non-commercial evaluation. Commercial deployment, white-label builds, custom features, source licensing, and OEM rights are available on order. See [Commercial licensing](COMMERCIAL.md).

## Highlights

- **App-specific protection:** select one or more launchable apps instead of disabling screen timeout globally.
- **Foreground-aware:** protection activates only when a selected app is actually in the foreground.
- **Manual lock remains authoritative:** pressing the power button still turns off and locks the device.
- **Battery-conscious:** the app permits dimming and uses a finite wake-lock timeout with renewal only while protection is still needed.
- **Reboot recovery:** monitoring can restart after reboot or an in-place app update when Android permits it and prerequisites remain satisfied.
- **Privacy-first:** no `INTERNET` permission, analytics SDK, Accessibility service, overlay permission, screen capture, message access, or file access.
- **Transparent state:** persistent foreground-service notification, live Off/Monitoring/Awake status, and a Stop action.
- **Local diagnostics:** the Info screen exposes permission and service state without collecting screen content.
- **No third-party runtime libraries:** the app uses Android platform APIs.

## How it works

```text
User selection
    │
    ▼
SharedPreferences
    │
    ▼
AppMonitorService ──► UsageStatsManager events ──► ForegroundAppTracker
    │                                              │
    ├── selected foreground app? ── yes ──► finite SCREEN_DIM_WAKE_LOCK
    │
    └── status ──► private in-package broadcast + foreground notification
```

The monitoring service polls usage transition events, keeps only the latest foreground package state, and checks that package against the user's selected set. A wake lock is held only while the display is interactive and a selected app remains active.

## Privacy and permissions

| Permission | Purpose |
|---|---|
| `PACKAGE_USAGE_STATS` | Reads foreground-app transition events after the user explicitly grants Usage Access. |
| `WAKE_LOCK` | Prevents inactivity sleep while a selected foreground app is active. |
| `FOREGROUND_SERVICE` | Runs continuous, user-visible monitoring. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Declares the app-specific foreground monitoring use case on modern Android. |
| `POST_NOTIFICATIONS` | Shows the monitoring notification on Android versions that require permission. |
| `RECEIVE_BOOT_COMPLETED` | Restores monitoring after reboot when the user previously enabled it. |

The manifest does **not** request internet, location, camera, microphone, contacts, SMS, storage, Accessibility, overlay, or unrestricted package-query permissions. See [Privacy design](docs/PRIVACY.md).

## Install

### From a GitHub Release

If the maintainer publishes a signed APK in GitHub Releases:

1. Download the APK from the repository's **Releases** page.
2. Open it on the Android device.
3. If Android requests permission to install from that source, grant it only for the installation source you trust.
4. Install and open **Sameer App Awake**.
5. Select the apps to protect and enable **Protect selected apps**.
6. Grant **Usage Access** when Android opens the system settings page.
7. Allow notifications so monitoring remains visible.

### From source

Requirements:

- JDK 17
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0 or newer
- Gradle wrapper included in this repository

Build from the repository root:

```bash
./gradlew lintDebug assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat lintDebug assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Tests

The dependency-free foreground ordering test runs on a desktop JDK:

```bash
bash tools/run_core_tests.sh
```

Expected result:

```text
PASS: ForegroundAppTracker ordering and validation checks
```

A complete Android build also requires the Android SDK and Gradle dependencies.

## Technical profile

| Item | Value |
|---|---|
| Language | Java 17 |
| Build configuration | Gradle Kotlin DSL |
| Android Gradle Plugin | 9.3.1 |
| Gradle distribution | 9.5.0 |
| Minimum SDK | 29, Android 10 |
| Compile / target SDK | 37, Android 17 |
| Foreground detection | `UsageStatsManager` transition events |
| Protection mechanism | finite `SCREEN_DIM_WAKE_LOCK` |
| Persistent state | app-private `SharedPreferences` |
| Network permission | none |

## Repository structure

```text
app/src/main/java/com/sameerali/appawake/   Android application source
app/src/main/res/                           icons, colors, themes, XML resources
tools/                                      dependency-free core test tooling
docs/                                       architecture, privacy, signing, release notes
.github/                                    CI and issue templates
```

## Android and Play distribution note

The project declares a `specialUse` foreground-service type. Android requires correct foreground-service type declarations on modern target SDKs, and Google Play requires foreground-service declarations in Play Console for apps targeting Android 14 or later. Store publication therefore needs a separate policy review in addition to a successful build and device test.

## Security

Never commit a signing keystore, signing password, `local.properties`, API credential, or private key. This prepared repository intentionally contains **no signing key material**. See [SECURITY.md](SECURITY.md) and [Signing and releases](docs/SIGNING_AND_RELEASES.md).

## Commercial licensing

Professional customization is available for organizations, consultants, and white-label deployments. Standard starting packages begin at **EUR 149**. See [COMMERCIAL.md](COMMERCIAL.md).

## License

This project is **source-available, not open-source**. Non-commercial evaluation is permitted under the repository license. Commercial and redistribution rights require written permission. See [LICENSE.md](LICENSE.md).

## Author

**Sameer Ali**  
Contact: **sameer43786@gmail.com**
