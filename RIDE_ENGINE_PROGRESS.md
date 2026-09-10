# Ride Engine — Progress Report

_Session report: MVP build/deploy, then the "Ride Engine V1 — Route Intelligence" milestone (GPS quality pipeline, route statistics, Strava-style Ride Detail screen)._

## 1. What exists now

`app/src/main/java/com/andrerinas/openheadunit/ride/`, organized domain → location → data → service → presentation:

| Layer | Files | Purpose |
|---|---|---|
| `domain/` | `RideState`, `RideEvent`, `RideStateMachine`, `RidePoint`, `Ride`, `RideMetrics`, `RidePointQualityPolicy`, `RideRawSample`, `RouteStatistics`, `RouteStatisticsCalculator` | Pure Kotlin: state machine, Haversine distance/duration, GPS fix quality filtering, and full route statistics (moving/stopped time, speeds, GPS quality). 43 unit tests, all passing. |
| `location/` | `RideLocationEngine`, `RideLocationMapper` | Independent GPS subscription (doesn't depend on Android Auto being connected). |
| `data/` | `RideEntity`, `RidePointEntity` (now with `accepted`/`rejectionReason`), `RideDao`, `RideDatabase` (schema v2), `RideRepository`, `RideDataMapper` | Room persistence, Room 2.6.1 (not 2.8.x — see §3). Every raw fix is stored regardless of quality verdict; nothing is thrown away. |
| (root) | `RideComponent` | Standalone object graph, deliberately separate from `AppComponent`. |
| `service/` | `RideTrackingService` | Foreground service (`location` type); routes each fix through `RidePointQualityPolicy`, batches `RideRawSample`s into Room, recovers an interrupted ride after process death. |
| `presentation/` | `RideTrackerFragment`/`ViewModel`, `RideHistoryFragment`/`Adapter`, `RideDetailFragment`/`ViewModel`, `RouteGeometryView` | Start/Stop screen, history list, and a Strava-style Ride Detail screen: the recorded route drawn full-bleed as geometry (no map tiles/API key), with distance/moving time/avg+max speed/duration/GPS quality floating beside it in two columns. |

## 2. Toolchain setup

This machine had no Android/Java/NDK toolchain. Installed via `sdkmanager`: `platforms;android-36`, `build-tools;36.0.0`, `ndk;29.0.14206865`, `cmake;3.22.1`, `platform-tools`; wrote `local.properties`.

## 3. Map provider investigation (your explicit ask)

Tested three options for rendering a recorded route:
- **osmdroid** — no API key, but the project was **archived Nov 2024**, no longer maintained. Ruled out.
- **MapLibre + OpenFreeMap** — actively maintained, genuinely free/no-API-key vector tiles (built for exactly this gap). Real test: MapLibre itself needs `minSdk 21` (fine, same tier as existing overrides), **but transitively pulls a newer AndroidX Fragment (1.8.2) than this project pins (1.6.2), which also needs `minSdk 21`** — and `Fragment` underlies every screen in this app, so forcing that dependency has real blast radius. Reverted rather than pushing through blind.
- **Google Maps SDK** — ruled out per your own reasoning (needs your own Google Cloud project/API key, doesn't fit local-first).

**Decision (your call, confirmed):** two passes. Pass 1 (done, this session) — route drawn as pure geometry (`RouteGeometryView`, a custom `Canvas` view: polyline + start/end markers, local equirectangular projection), zero new dependency, zero risk. Pass 2 (later, separate spike) — real MapLibre + OpenFreeMap tiles, after verifying on-device whether force-pinning `fragment-ktx` back to 1.6.2 is actually safe at runtime.

## 4. Two Room dependency conflicts resolved (from the original MVP build)

- **Room 2.8.5 required Kotlin 2.1 metadata**, incompatible with this project's pinned Kotlin 1.9.22 → used **Room 2.6.1** instead of bumping the whole project's Kotlin version.
- **Room requires `minSdk 23`**, above both flavors' declared `minSdk` (16/21) → resolved via the manifest's existing `tools:overrideLibrary` mechanism (already used for Shizuku/GMS) rather than raising the app-wide `minSdk`.

## 5. Bugs found and fixed on real hardware

**In the Ride Engine code:**
1. **NPE on launch** — the new "Ride Tracker" button was only added to `res/layout/fragment_home.xml` (landscape/default); portrait phones load a *separate* file, `res/layout-port/fragment_home.xml`. Fixed by adding it there too.
2. **Crash on tapping Start** — `LocationManager.requestLocationUpdates()` without an explicit `Looper` needs a thread that has one prepared; `RideTrackingService` calls it from a background coroutine dispatcher, which has none (`GpsLocation.kt`, the file this was modeled on, gets away with it only because it's always called from the main thread). Fixed by passing `Looper.getMainLooper()` explicitly.

**Pre-existing bugs, unrelated to the Ride Engine, all now properly fixed (not worked around):**
3. **`getBondedDevices()` crash on any fresh install** — called on every `HomeFragment.onResume()` (native Android Auto auto-connect check) without checking `BLUETOOTH_CONNECT` on API 31+. Traced the existing permission architecture (`BluetoothDevicePicker.kt` already had the correct guard elsewhere in the codebase) and applied the identical pattern to both call sites in `HomeFragment.kt` (`checkNativeDriverSelectionOnStartup()`, `showNativeAaDeviceSelector()`).
4. **Two lifecycle races in the same native-AA device-selector dialog** — `setOnShowListener` and `setOnDismissListener`/`cancelDriverSelection()` both called `requireContext()` without checking `isAdded`, crashing if the dialog's callback fired after the fragment was torn down. Fixed with the same `isAdded` guard already used by the dialog's `onFinish()` callback a few lines away.

All four confirmed fixed via repeated on-device crash-log verification, not just compile checks.

## 6. GPS quality pipeline (new this session)

`RidePointQualityPolicy.evaluate(candidate, previousAccepted)` rejects a fix for: poor accuracy (>50m), non-monotonic timestamp, duplicate (<200ms since last accepted), impossible jump (>300 km/h implied speed), or implausible reported speed. **Every fix is still persisted** (`RideRawSample` + `RidePointEntity.accepted`/`rejectionReason`) — quality filtering only decides what counts toward the route, never what gets stored, so thresholds can change later without losing data. `RideTrackingService` now routes every incoming fix through this before updating the live running distance.

`RouteStatisticsCalculator.compute()` derives, from a ride's raw samples: distance, duration, moving/stopped time (1 m/s threshold), average moving speed, max speed (prefers each fix's own Doppler speed over a computed leg speed), average/worst accepted accuracy, and accepted/rejected point counts. Always recomputed from raw samples on demand (Ride Detail screen) — nothing is cached beyond what `RideEntity` already stored for the history list.

## 7. Verification performed

- Full unit test suite: 48 tests, all passing (`RidePointQualityPolicyTest` 11, `RouteStatisticsCalculatorTest` 8, plus the original 24 domain tests).
- On-device, driven via `adb` (UI hierarchy dumps + tap coordinates + screenshots, since no emulator/second device is available for you to drive manually mid-session):
  - Full Start → Stop → History cycle, twice, with no crash — confirmed real `RideEntity` rows persist correctly.
  - Seeded a synthetic 60-point loop route directly into the local Room DB (bypassing the quality filter, so its resulting speed numbers are physically unrealistic — a test-data artifact, not a pipeline bug) purely to verify the **Ride Detail screen's rendering** without needing an actual outdoor GPS fix. Confirmed: route polyline + start/end markers render correctly, stat overlay populates correctly, and a real layout bug (`ride_detail_date` constrained to a `MaterialToolbar` id nested inside an `AppBarLayout`, which `ConstraintLayout` can't resolve across that nesting) was caught and fixed by constraining to the `AppBarLayout`'s own id instead.
  - Cleared all test/seeded data from the device afterward — it now starts with a genuinely empty ride history, ready for a real ride.

## 8. Git state at time of writing

Nothing has been committed. `git status --porcelain`:
```
 M app/build.gradle.kts
 M app/src/main/AndroidManifest.xml
 M app/src/main/java/com/andrerinas/openheadunit/App.kt
 M app/src/main/java/com/andrerinas/openheadunit/main/HomeFragment.kt
 M app/src/main/res/layout-port/fragment_home.xml
 M app/src/main/res/layout/fragment_home.xml
 M app/src/main/res/navigation/nav_graph.xml
 M app/src/main/res/values/strings.xml
?? RIDE_ENGINE_PROGRESS.md
?? app/src/main/java/com/andrerinas/openheadunit/ride/
?? app/src/main/res/drawable/ic_stat_ride_tracking.xml
?? app/src/main/res/layout/fragment_ride_detail.xml
?? app/src/main/res/layout/fragment_ride_history.xml
?? app/src/main/res/layout/fragment_ride_tracker.xml
?? app/src/main/res/layout/list_item_ride.xml
?? app/src/main/res/layout/stat_overlay_item.xml
?? app/src/test/java/com/andrerinas/openheadunit/ride/
```
(`row_ride_stat.xml`, from the first Detail-screen draft, was deleted again in the same session when the layout was redesigned — never shipped.)

## 9. What's left

**Right now**
1. Commit the work.
2. Take it on the NS400Z — the real engineering validation run (GPS accuracy, vibration effects, route quality, missed points, battery drain, service reliability, A23 mount position).

**Near-term / fast-follow**
3. Pass 2 of the map work: spike whether force-pinning `fragment-ktx` to 1.6.2 is safe with MapLibre, then swap `RouteGeometryView`'s flat background for real MapLibre + OpenFreeMap tiles.
4. Promote the "Ride Tracker" home-screen button from its corner placement into the actual button grid.

**Deferred on purpose, informed by the real ride's data**
5. Automatic ride detection — `POSSIBLE_RIDE`/`PAUSED` already modeled in the state machine, unused until this lands.
6. Sensor telemetry (accelerometer/gyro/rotation vector → lean angle, braking, g-force).
7. Google Maps Timeline import.

**Further out**
8. Embedding ride telemetry alongside the Android Auto projection surface, then eventually a custom launcher/HUD.
