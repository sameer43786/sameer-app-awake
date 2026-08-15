# Sameer App Awake v1.0.0

**By: Sameer Ali | Contact: sameer43786@gmail.com**

## Release summary

Sameer App Awake v1.0.0 is the first portfolio release of a privacy-first Android utility that keeps the display awake only while user-selected applications remain in the foreground.

## Highlights

- Select one or more launchable Android applications for screen-awake protection.
- Detect foreground transitions through Android Usage Access rather than Accessibility or overlays.
- Hold a finite screen-dim wake lock only while a selected app remains active.
- Preserve normal manual locking with the device power button.
- Display continuous monitoring state through a foreground-service notification.
- Restore monitoring after reboot or package update when Android permits it and prerequisites remain satisfied.
- Operate without the Android `INTERNET` permission or third-party runtime libraries.
- Include a dependency-free JVM test for foreground-event ordering and validation.

## Verification

The dependency-free core test passes in the publication audit environment. A full Android Gradle build requires an Android SDK and network access for build dependencies and should be run in the maintainer's development environment before attaching a release APK.

## Commercial availability

Commercial internal-use licensing, small-team licensing, white-label builds, source-code licensing, OEM/resale rights, and custom engineering are available. See `COMMERCIAL.md` for starting prices and scope.

## Security note

Release APKs must be signed only from a private signing environment. Never publish a signing keystore, signing password, `local.properties`, or private key.
