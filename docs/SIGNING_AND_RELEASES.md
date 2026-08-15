# Signing and Releases

By: Sameer Ali | Contact: sameer43786@gmail.com

## Critical rule

**Never commit a signing keystore or its password.** Keep production signing material outside the repository and outside cloud-synced folders unless the storage is specifically designed for secrets.

The public repository should contain no `.jks`, `.keystore`, `.p12`, private-key PEM, or signing-password file.

## Recommended release workflow

1. Build and test a debug APK.
2. Run `./gradlew lintDebug assembleDebug`.
3. Test installation, Usage Access, app selection, foreground transitions, manual power-button lock, notification Stop action, and reboot behavior on target devices.
4. Build the release variant using a private signing configuration that is not committed to Git.
5. Verify the APK signature and version.
6. Create a Git tag such as `v1.0.0`.
7. Create a GitHub Release from that tag.
8. Attach only the intended signed APK and public release notes.
9. Never attach a keystore, password file, `local.properties`, or developer machine configuration.

## Google Play

A Play Store release requires additional policy and foreground-service declarations. Treat GitHub APK distribution and Google Play publication as separate release tracks.
