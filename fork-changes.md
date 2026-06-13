# Fork changes — iFit V2 / NordicTrack T Series 9 support

This fork extends Hyperborea's **FitPro V2** support (iFit V2 controllers, USB
product ids 3 and 4), validated on a **NordicTrack T Series 9** treadmill
(model `ETNT15425-INT`, part number `455916`, FitPro V2 / USB `213c:0003`).

All changes are **V2-only** — the V1 path and the host-driven V2 path (the
upstream "LargeX" console) are left untouched, by construction where possible.

## Summary

| # | Commit | Change |
|---|--------|--------|
| 1 | `detect the V2 equipment type during probe` | Idle dashboard shows the real device type, not a generic bike |
| 2 | `read V2 belt speed from TARGET_KPH` | Speed reaches the app and Zwift (the treadmill never sends `CURRENT_KPH`) |
| 3 | `stop double-driving V2 treadmills` | No more belt-starts-at-arm, speed jumps, or start conflicts |
| 4 | `catalog the NordicTrack T Series 9` | Correct device name + speed/incline bounds |

---

## 1. Detect the V2 equipment type during the probe, not just at connect

**Problem.** At idle the dashboard showed a V2 treadmill as a generic **bike**
("FitPro Device"). The probe path (`FitProAdapter.identify` →
`V2Session.identify`) only queried features and product info; it never resolved
the equipment type, so `DeviceInfo` fell back to the catalog default (BIKE). The
type was corrected only later, in the full connect/start path.

**Fix.**
- `V2Session.identify()` now resolves the type from the supported-features
  heuristic (no subscription needed in the probe).
- `FitProAdapter.identify()` captures that type (`if (session is V2Session)`)
  before building the probe's `DeviceInfo`. V1's identify path is untouched.

This also lets FTMS pick the **Treadmill Data** characteristic (`0x2ACD`)
instead of Indoor Bike Data for clients like Zwift.

**Files:** `hardware/fitpro/.../v2/V2Session.kt`,
`hardware/fitpro/.../FitProAdapter.kt`.

## 2. Read V2 belt speed from `TARGET_KPH`

**Problem.** The treadmill never emits `CURRENT_KPH`. The belt runs at the
commanded speed reported in the writable `TARGET_KPH` field, which is the actual
speed for a belt machine. The displayed/broadcast speed was wired only to
`CURRENT_KPH`, so it sat at 0 for the whole workout — the app showed 0 km/h and
Zwift saw a stationary runner and never moved, even though distance/calories
climbed.

**Fix.** Subscribe to `TARGET_KPH` and, **on belt machines only**, drive the
displayed speed from it (in addition to `targetSpeed`). Bikes/ellipticals report
a real `CURRENT_KPH` and keep `TARGET_KPH` as a pure target, so the belt-only
guard leaves them untouched. Mirrors the `isBeltBased` "speed lives in the
writable field" rule already documented for V1.

**Verified.** During a run, `CURRENT_KPH` was sent 0 times while `TARGET_KPH`
tracked every speed change; the dashboard speed then followed the belt and
matched the speed derived from the `DISTANCE`/`RUNNING_TIME` deltas.

**Files:** `hardware/fitpro/.../v2/V2FeatureId.kt`,
`hardware/fitpro/.../v2/V2Session.kt`.

## 3. Stop double-driving V2 treadmills that run their own state machine

Three control bugs, all from the host driving a console that already drives
itself (the T Series 9 MCU self-drives speed, start/stop and incline; the
upstream LargeX forwards keys and acts on nothing):

- **Belt started at arm**, before the physical Start. Arming wrote
  `WORKOUT_STATE = WARM_UP`, and this console treats `WARM_UP` as "run the belt".
  → Don't write `WARM_UP` at arm for treadmills; stay idle and let the physical
  Start drive things. The LargeX rejected that write from idle anyway and waits
  for its own `READY_TO_START`, so it's unaffected.
- **Speed jumped erratically** (one press moved it twice; a few Down presses
  overshot to 0 and tripped the console's auto-pause). The MCU acts on its own
  speed/incline keys **and** we re-routed them as `AdjustSpeed`/`AdjustIncline`.
- **Physical Start fought the console's own start**: we forced an initial 0.5
  kph over the speed the console picked.

**Fix.** Detect when the console drives the belt itself — it reports a
`TARGET_KPH` we never commanded — and latch `mcuDrivesBelt`. While latched,
suppress host key-routing and host-driven start, and skip the initial-speed
command. Consoles that forward keys but act on nothing (the LargeX, which never
self-reports `TARGET_KPH`) never latch it and keep the full host-driven path.
Also re-sync `lastSentSpeed` to the console's reported speed.

**Verified.** Belt stays put until the physical Start; speed/incline keys step
once each; Stop follows the console's native two-press pause-then-end.

**Files:** `hardware/fitpro/.../v2/V2Session.kt`.

## 4. Catalog the NordicTrack T Series 9

**Problem.** The T Series 9 (part number `455916`) isn't in the auto-generated
`IfitDeviceCatalog`, so it resolved to a generic "FitPro Device" with bike
bounds — wrong name in the app/Zwift, and a 60 km/h / 20% range that doesn't
match the belt.

**Fix.** Add a small hand-maintained `EXTRA_PART_NUMBERS` override map (checked
first in `lookupPartNumber`) for V2 consoles that ship before iFit's
part-number export catches up. Seeded with the T Series 9 from its spec sheet:
treadmill, **0–20 km/h, 0–12% incline**. Editing the generated parallel arrays
by hand would be error-prone and lost on regeneration; the override keeps
hand-added entries separate and obvious.

**Files:** `hardware/fitpro/.../session/DeviceDatabase.kt`.

---

## Hardware findings (NordicTrack T Series 9, FitPro V2)

Observed via ADB logcat on the console (USB `213c:0003`, model
`ETNT15425-INT`, fw `1.19.2e177c9e6`):

- **`CURRENT_KPH` (302) is never sent.** Belt speed lives in **`TARGET_KPH`**
  (301). Incline works via **`CURRENT_GRADE`** (402). No `WATTS`/`RPM` →
  power/cadence are always null (normal for a treadmill; Zwift running doesn't
  need power).
- V2 events fire **on change only**.
- `DISTANCE`/`TOTAL_MACHINE_DISTANCE` are **metres** on the wire (the session
  divides by 1000 → km). `RUNNING_TIME` is seconds.
- The **MCU drives its own state machine and keys**: `NONE→WARM_UP→RUNNING` on
  the physical Start, its own mph-based `TARGET_KPH` per speed key, its own
  incline. Writing `WORKOUT_STATE = WARM_UP` **runs the belt** (~1.6 km/h warmup).
- **Physical Stop is two-press**: 1st → `PAUSED` (belt stops), 2nd → `RESULTS`
  (session ends). Hyperborea ends the session on `RESULTS`/idle.
- **Host incline control works**: a FTMS Control Point "Set Target Incline"
  written to Hyperborea drives the physical ramp (verified 4%→10%→0%, with
  `CURRENT_GRADE` tracking).

## Not done — Zwift treadmill auto-incline

Investigated, then dropped. The hardware side works (above), but **Zwift does
not send incline** to a generic FTMS treadmill — it gates treadmill incline
control on a device whitelist (Wahoo KICKR Run). Spoofing the advertised name to
"KICKR RUN" makes Zwift **recognise** the device, but it still sends **no FTMS
control-point command**: the real KICKR Run is controlled over a **proprietary
Wahoo BLE service**, not standard FTMS. Full emulation would mean implementing
that proprietary service (as qdomyos-zwift does) — a separate, larger effort.

## Build environment

`minSdk 22 … compileSdk/targetSdk 36`, AGP 9.1, Gradle 9.3.1, Kotlin 2.3.20 →
**JDK 17** + Android SDK platform-36/build-tools 36/platform-tools. Build with
`./gradlew :app:assembleStandardDebug`; tests with `./gradlew :hardware:fitpro:test`
(use the `testDebugUnitTest` task for `--tests` filtering).
