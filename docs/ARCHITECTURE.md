# Architecture

By: Sameer Ali | Contact: sameer43786@gmail.com

## Components

### `MainActivity`

Provides the user interface, lists launchable applications, handles search and selection, requests Usage Access and notification permission, starts or refreshes monitoring, displays current state, and exposes diagnostics.

### `AppPreferences`

Centralizes app-private `SharedPreferences`. Persisted values include selected package names, monitoring state, latest service state, protected package, last status time, and concise local error state.

### `AppMonitorService`

Runs as a user-visible foreground service. It queries usage transition events on a scheduled worker, determines the current foreground package, compares it with the selected set, and acquires or releases a finite screen wake lock.

### `ForegroundAppTracker`

A dependency-free state object that rejects older overlapping usage events. This isolates event-ordering logic from Android APIs and allows JVM testing.

### `BootReceiver`

Attempts to restore monitoring after boot or an in-place package replacement only when monitoring was enabled, packages remain selected, and Usage Access is still available.

### `PermissionUtils`

Encapsulates Usage Access checks, system settings navigation, notification permission checks, and package-label lookup.

## Main state flow

```text
launchable apps ──► user selection ──► SharedPreferences
                                          │
                                          ▼
                                  AppMonitorService
                                          │
                   UsageStatsManager ─────┤
                                          ▼
                               ForegroundAppTracker
                                          │
                         selected? + display interactive?
                              │                     │
                             yes                    no
                              │                     │
                              ▼                     ▼
                     acquire/renew wake lock   release wake lock
                              │                     │
                              └─────────┬───────────┘
                                        ▼
                              notification + UI status
```

## Timing safeguards

- Usage events are polled approximately every 800 ms.
- Event queries overlap by 2 seconds to tolerate timing boundaries.
- The initial event query looks back 30 minutes.
- The wake lock has a 10-minute maximum hold and is renewed after 8 minutes only if protection is still required.

## Trust boundary

The application trusts Android's Usage Access event stream and package manager. It does not use a network backend. Package selection and runtime status remain in app-private local storage.
