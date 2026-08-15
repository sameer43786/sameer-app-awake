# Privacy Design

By: Sameer Ali | Contact: sameer43786@gmail.com

## Data accessed

After the user grants Android Usage Access, the app reads package transition events needed to identify which application is in the foreground.

## Data not accessed

The application does not request permissions for internet access, location, camera, microphone, contacts, SMS, call logs, external storage, Accessibility, overlays, or screen capture. It does not intentionally read screen text, messages, keystrokes, files, browsing content, or network traffic.

## Data stored locally

The app stores selected Android package names and monitoring/status metadata in app-private `SharedPreferences`. No cloud account or remote database is used.

## Network behavior

The manifest contains no `INTERNET` permission. The application therefore has no normal Android network-socket capability.

## Diagnostics

Diagnostics are intended to describe permission and service state. Users should still review copied diagnostics before public sharing because package names and software configuration can provide contextual information.
