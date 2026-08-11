# Working plan

Scratch file for picking work back up after an interruption. Not user documentation.

## Standing instructions

These apply to every future change, without being asked again:

1. **Commit often**, in small meaningful pieces, one concern per commit.
2. **Always push** to `origin main` when commits exist. Never wait to be told.
3. **Always export the smallest possible release APK to `~/Desktop`** after a change.
4. Verify on the emulator rather than assuming a clean compile means it works.

## State as of this file being written

- Last commit, local and pushed, in sync: `4511431 Wire favorites into the map screen`
- 21 commits total on `main`.
- Working tree has **uncommitted, compiling, untested** work. Nothing is broken;
  it simply has not been run on a device, committed, or pushed yet.

### Uncommitted work in the tree

| File | Change | Status |
| --- | --- | --- |
| `ui/Haptics.kt` (new, untracked) | Haptic vocabulary: `tick()`, `confirm()`, `reject()`, `toggle(on)` over Compose `LocalHapticFeedback` | compiles |
| `ui/MainScreen.kt` | `ControlButton` tint fix + haptics wired into ~10 interactions | compiles |
| `ui/PlaceSearchBar.kt` | Clears focus when the IME is dismissed | compiles |
| 12 × `res/drawable/ic_*.xml` | Stripped baked `android:tint` | compiles |

**The dark mode icon bug and its fix.** The bookmarks icon rendered black on a dark
surface. Cause was not the drawable: `ControlButton` declared
`tint: Color = LocalContentColor.current`, and a default argument is evaluated in the
function's own scope, which is *outside* the `Surface` it then draws. So it resolved the
ambient content colour over the map (black) rather than the surface's content colour. The
zoom buttons were unaffected because their `Icon` sits inside the `Surface` and reads the
local itself. Fixed by making the parameter `Color?` and resolving `?: LocalContentColor.current`
inside the `Surface`. The `android:tint` strip is a separate tidy-up so Compose alone owns
icon colour; `ic_notification.xml` deliberately keeps its tint because the framework, not
Compose, renders it.

## Next steps, in order

### 1. Verify and land the work already in the tree
- Install on the emulator, confirm in **dark mode** that the bookmarks icon is no longer black.
- Confirm haptics fire and that dismissing the keyboard drops the field's focus.
- Commit as three separate commits: icon tint fix / haptics / focus clearing.
- Push. Export APK to Desktop.

### 2. My-location button (task #9)
- New `geo/CurrentLocation.kt`: suspend wrapper over
  `LocationManager.getCurrentLocation(provider, null, executor, consumer)` (API 30+, fine at
  minSdk 33). Try `FUSED_PROVIDER`, then `GPS_PROVIDER`, then `NETWORK_PROVIDER`.
- Request `ACCESS_FINE_LOCATION` at the tap if not already granted. It is already declared in
  the manifest and already requested for the foreground service, so this reuses that flow.
- Put the button in the right-hand control column above the zoom buttons, separated by a
  divider. `ic_my_location.xml` already exists.
- **Gotcha worth handling:** if a spoof is running, this app *is* the location provider, so the
  returned fix is our own mock. Check `Location.isMock` and say so rather than silently flying
  the map to the fake position.

### 3. Route simulator (task #10)
Move between saved places. Design settled on:

- **`map/Route.kt`** — great-circle maths: haversine distance, spherical interpolation between
  two points, and bearing. Linear lat/lon interpolation was rejected; it visibly deviates over
  long legs.
- **`MockLocationEngine.push`** — currently hard-codes `bearing = 0` and `speed = 0`. Take both
  as parameters so a moving fix looks like real movement.
- **`SpoofService`** — accept `List<GeoPoint>` plus a speed instead of a single point. One point
  keeps today's static behaviour; two or more advances along the route each tick, recomputing
  position, bearing and speed. Needs a `SpoofState` that carries the live point.
- **UI** — route selection inside the existing saved-places sheet rather than new chrome: a
  route toggle in the sheet header (only when there are 2+ saved places), rows showing an order
  badge while selecting, and a footer with speed presets (walk / cycle / drive) and a start
  button.
- **Camera** — follow the simulated position while a route runs, so the centre pin stays on it.
  Add a `follow()` on `MapCameraState` that animates the centre only: no zoom change and none of
  `animateTo`'s zoom-arc logic, which exists for long jumps and would fight per-tick updates.

### 4. APK export to Desktop (task #11)
- Release APK is unsigned, so it cannot be installed as-is. Sign with the debug keystore for
  convenience and **say clearly in the summary that it is debug-signed**, not release-signed.
- Copy to `~/Desktop/OpenSpoof.apk`.
- Worth investigating for size: the merged baseline profile
  (`assets/dexopt/baseline.prof`, from material3's `baseline-prof.txt`). Dropping it would save
  space at a real startup-performance cost — measure it first, then raise the trade-off rather
  than deciding silently.

## Notes on this emulator

Unreliable for UI automation, which cost time earlier:

- Unrelated apps (OpenClone, BlackBox, an onboarding screen) repeatedly steal foreground
  mid-test. Always re-assert focus before each `input tap`:
  `dumpsys window | grep mCurrentFocus`, relaunch and retry if it is not OpenSpoof.
- Its clock jumps minutes per second, so timestamps in logs are not wall-clock.
- Prefer driving the UI by keyboard action (`KEYCODE_ENTER`) over tapping coordinates.
- `run-as` cannot read app data from the **release** build, only the debug build, so verify
  release behaviour visually or with `uiautomator dump`.
- A physical phone briefly auto-connected over adb during an earlier session and received an
  install. Check `adb devices` before installing.

## Verified working, do not re-litigate

Spoofing itself is confirmed end-to-end on the R8 release build: providers `gps`, `network`
and `fused` all report the mock fix, the developer-options dialog gates it correctly and
auto-dismisses on the app-op being granted, favourites persist and reload, and picking a saved
place flies the camera back to it exactly.
