# 01 · Feasibility: replacing the HyperOS 2 charging animation

Status: **confirmed**, stage 0 and stage 1 implemented and verified on hardware.
Date of measurements: see device facts below.
Primary device: Redmi K60 (`23013PC75G` / `mondrian_eea`), HyperOS `OS2.0.208.0.VMNEUXM`,
Android 15 (SDK 35), display 1440x3200 @ 560dpi (~411 x 914 dp), SystemUI runs as uid `system` (1000).

## Status

| Stage | Content | State |
| --- | --- | --- |
| 0 | libxposed 102 module skeleton, scope, activation | done, verified in logcat |
| 1 | Suppress both ROM charge visuals, own overlay window, ROM agnostic plug detection | done, verified on hardware |
| 2 | Port the `zmd-charge` timeline and renderer | done, reference invariants ported as unit tests, A/B verified |
| 3 | Battery data (mWh), settings screen and live delivery to SystemUI | done, verified on hardware |
| 4 | OnePlus 15 / OxygenOS 16 adapter | almost done |

### Stage 3 notes: getting settings into SystemUI

libxposed remote preferences are the idiomatic channel and the hook side reports
`framework=Vector remote=true`, so reading them works. Writing them does not, in this setup: the
binder the module app needs is pushed by the framework's **manager app**, and no Vector manager is
installed here (the framework is driven through `/data/adb/lspd/cli`). Without a manager the module
app never receives a binder, so `XposedServiceHelper` stays empty and nothing is ever written.
Toggling the module and reinstalling it did not trigger a push either.

The channel is therefore a `ContentProvider` owned by the module app (`SettingsProvider`, authority
`<package>.settings`), which SystemUI reads over the resolver:

```
module app --call(set)--> SettingsProvider --notifyChange--> ContentObserver in SystemUI
                                                                   |
                                                        ContentSettings.current()
                                                                   |
                                                     EndfieldHudController.applySettings()
```

* Exported without a permission on purpose. The payload is a handful of display preferences, and a
  signature permission cannot work because SystemUI is not signed with the module's key.
* The provider is the single store of record, so the app and the hook cannot disagree about where
  the settings live.
* The provider also accepts the JSON as the `call` **argument**, not only in the extras bundle:
  `adb shell content call --extra` cannot carry a value containing colons, which makes scripted
  writes impossible otherwise. `references/_scratch/probe_settings.sh` uses that path.
* This dropped the `io.github.libxposed:service` dependency entirely, which also removed the
  compileSdk 37 requirement that service and interface 102.0.0 carry.

Verified end to end on hardware:

```
$ adb shell sh /data/local/tmp/probe_settings.sh zh
Result: Bundle[{json={...,"language":"zh",...}}]
I/EndfieldCharge: [settings] changed to HudSettings(..., language=zh, ...)
I/EndfieldCharge: [hud] settings applied: HudSettings(..., language=zh, ...)
```

and the HUD then drew `超充模式` instead of `Super Charge Mode` without restarting SystemUI.

### Stage 3 notes: handing the animation back

Suppression is decided per hook call, not at install time:

```kotlin
.intercept { chain ->
    if (suppressionActive()) null else chain.proceed()
}
```

so turning "Replace the ROM animation" off in the settings screen gives the ROM its animation back
without restarting SystemUI, and turning it on again takes it away. `suppressionActive()` reads the
settings and defaults to true, so an unreachable provider leaves the module doing its job rather
than silently doing nothing.

Measured on hardware, plugging in while unlocked:

| Module | ROM side | Module side |
| --- | --- | --- |
| off | `showChargeAnimation` -> `init strong toast` -> `StrongToast: addView` | no window attached |
| on | `showChargeAnimation` -> suppressed | `[window] HUD window attached (type=2026)` |

One trap worth remembering: a screenshot taken during that test appeared to show both the ROM toast
and the HUD. The second pill was the settings screen's own preview strip, with the app still in the
foreground. `dumpsys window windows` settled it - our overlay was genuinely absent.

### Stage 2 notes

The timeline is a segment by segment port of `Animations/HudAnimations.cs`. Avalonia's `KeySpline`
and Android's `PathInterpolator` have identical semantics, including the `BackOut` overshoot
(second control point Y = 1.275), so no easing had to be approximated.

`core/timeline/HudCues.kt` holds the cue table and the remapping and deliberately has no Android
imports, so `app/src/test/.../HudCuesTest.kt` can run it on the JVM. Those tests are the reference
project's own `HudAnimationsTests.cs` vectors: identity at 6 s, strict monotonicity across every
baseline cue at 3/4.5/6/8/10 s, pinned endpoints, and the invariant that the intro keeps its
absolute 2.52 s. Run with `.\gradlew.bat testDebugUnitTest`.

Two deliberate deviations, both forced by the platform:

1. **Scale.** 560 design units x 0.8 global scale is 448 dp, wider than the 411 dp screen.
   `uiScale = min(0.8, screenWidthDp * 0.92 / 560)` (about 0.676 on the K60) and every length,
   radius, font size and stroke width scales with it.
2. **Font.** The reference calls `WithInterFont()`, so its Latin text and numerals are Inter. Inter
   is bundled (`assets/fonts/Inter-Variable.ttf`, OFL) and applied through
   `Typeface.Builder(...).setFontVariationSettings("'wght' 500|700")`. The title and tagline keep
   the system CJK face, which is the Android equivalent of the reference's HarmonyOS Sans SC
   fallback. Inside SystemUI the font has to be read through
   `createPackageContext(modulePackage, 0).assets`, because the ambient context belongs to SystemUI.

A third deviation is phone-only: the reference sits 4 logical pixels below the top of the screen, but
the K60's display cutout is `Rect(0, 120 - 0, 0)` (120 px, about 34 dp) and centred, which is exactly
where the bolt sits in states A and B. `HudMetrics` places the window at
`max(cutout, statusBar) + 8 dp`, and the debug screen's offset slider reads the same value so the
preview matches the overlay.

### Verifying the animation without a camera

Screenshots and screen recordings are a poor instrument here: `screencap` on this device takes
about 1.8 s per frame, scrcpy records variable frame rate, and `screenrecord` produced 4 frames for
16 s. `debug/FrameSheetRenderer.kt` instead renders the same `EndfieldHudView` off screen at known
cues and writes a PNG per cue:

```
adb shell am start -n com.lemoneko.endfieldcharge/.ui.MainActivity --ez renderSheets true
adb pull /sdcard/Android/data/com.lemoneko.endfieldcharge/files/frames
```

The activity also plays both timelines in an ordinary app process, so iteration does not require
touching SystemUI at all.

### A/B against the reference implementation

`tools/capture-reference-hud.ps1` runs `references/zmd-charge/publish/EndfieldCharge.exe --demo`,
finds the HUD window by handle and captures its rectangle at about 143 fps. It has to call
`SetProcessDPIAware` first: `GetWindowRect` virtualises coordinates for a DPI unaware process while
`CopyFromScreen` works on the physical desktop, so on this 125 % display the first attempt came out
offset by 200 px and undersized (448x72 instead of 560x90).

Comparing the captured frames against `FrameSheetRenderer` output, both scaled to 560x90 so that one
design unit is one pixel, and measuring bounding boxes by colour:

| Element | Reference | Port |
| --- | --- | --- |
| pill | x 0..559 | x 0..559 |
| bolt square (state C) | x 26..42, y 36..52, centre (34, 44) | x 26..42, y 36..53, centre (34, 44.5) |
| bolt circle (state B) | x 85..116 (32x32), centre (100.5, 43.5) | x 85..115 (32x32), centre (100, 44.5) |
| badge accent | x 511..553, y 21..66, centre x 532 | x 510..554, y 22..67, centre x 532 |
| numbers, left edge | x 66 | x 65 |
| title block, vertical | y 41..64 | y 42..66 |

Everything lands within a pixel; the residual is sub-pixel rounding in vertical centring. The title
block width differs only because the reference machine is on a Chinese locale ("超充模式") while the
test device is on English ("Super Charge Mode").

Stage 1 evidence (`adb logcat -s EndfieldCharge:* -s EndfieldCharge/HyperOS:*`):

```
I/EndfieldCharge: loaded: process=com.android.systemui systemServer=false
I/EndfieldCharge: packageReady: com.android.systemui first=true classLoader=dalvik.system.PathClassLoader
I/EndfieldCharge: using ROM adapter: HyperOS (com.miui.charge)
I/EndfieldCharge: [ctx] hooked Application#onCreate for context capture
I/EndfieldCharge/HyperOS: suppression hooks installed
I/EndfieldCharge: [ctx] SystemUI context captured: com.android.systemui
I/EndfieldCharge: [battery] battery watcher registered
I/EndfieldCharge: [hud] controller started
I/EndfieldCharge: [hud] initial state: level=100% plugged=1 status=5 chargeFull=6200000uAh counter=6200 voltage=4399000uV
...
I/EndfieldCharge: [hud] unplugged (level=100%)
I/EndfieldCharge: [window] HUD window attached (type=2026)
D/MIUIStrongToastControl: showChargeAnimation                      <- ROM wants its toast
I/EndfieldCharge/HyperOS: showCustomStrongToast(category=charge)   <- our hook sees it
I/EndfieldCharge/HyperOS: suppressed charge strong toast           <- dropped
I/EndfieldCharge: [hud] plugged in (level=100%)
```

The ROM's `MIUIStrongToast` never reaches `init strong toast` / `addView`, and the screenshot shows
only the HUD pill. `references/_scratch/hud_plug_unlocked.png` and `hud_plug.png` (lockscreen) are
the captured evidence.

`onPackageReady` is delivered **twice** for `com.android.systemui` with `first=true`; the module
guards with an `installed` flag. Both deliveries carry a `dalvik.system.PathClassLoader`.

All class names, field names and control flow below were read from the decompiled
`/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk` of that exact build.
Re-derive with the recipe in [Reproducing](#reproducing) instead of trusting this file blindly
after a ROM update.

---

## 1. What the ROM does

> **Correction after on-device testing.** HyperOS has **two independent** charging visuals, and
> which one you get depends on whether the keyguard is showing. The decompiled call chain below
> covers only the first one. See [1b](#1b-the-second-path-charging-strong-toast) for the one that
> actually fires on this device when the phone is unlocked.

The lockscreen animation lives in the `com.android.systemui` process, package `com.miui.charge`.

```
MiuiChargeManager.start()                                  // ILateInitializer
  registerReceiver(ACTION_BATTERY_CHANGED, priority=1001)
  registerReceiver("miui.intent.action.ACTION_QUICK_CHARGE_TYPE")
    -> maintains MiuiBatteryStatus{level, plugged, wireState, chargeSpeed,
                                   chargeDeviceType, maxChargingWattage, status}
    -> notifyBatteryStatusChanged() -> KeyguardUpdateMonitor msg 302
        -> MiuiChargeController.checkBatteryStatus(status, clickShow)
             shouldShowChargeAnim()
             showChargeAnimation(wireState)
               prepareChargeAnimation()
                 new MiuiChargeAnimationView(context)
                 view.addChargeView()
                   NotificationShadeWindowView.addView(view, MATCH_PARENT x MATCH_PARENT)
               PowerManager.wakeUp(uptimeMillis, "com.android.systemui:RAPID_CHARGE")  // if screen off
               mScreenOnWakeLock.acquire(view.getAnimationDuration())                  // 20000 ms
               handler.postDelayed(mScreenOffRunnable, view.getScreenOffTime())         // 19400 ms
```

### Facts that matter for us

| Fact | Evidence in decompiled source |
| --- | --- |
| The animation view is **not** a standalone window. It is a child of the notification-shade root view. | `MiuiChargeAnimationView.addChargeView()` sets `mShowChargingInNonLockscreen = false` unconditionally, then branches `if (mShowChargingInNonLockscreen) mWindowManager.addView(...) else getNotificationShadeWindowView().addView(...)`. The window branch is dead code in this build. |
| The animation only plays while the **keyguard is showing**. | `MiuiChargeController.checkBatteryStatus()` guards `showChargeAnimation(i)` behind `zIsKeyguardShowing` (plus `!mKeyguardGoingAway`, `!isKeyguardOccluded`, `!isInEnterEditorMode`, `!isPrimaryBouncerIsOrWillBeShowing`, `!isOccludedAnimationPlaying`). |
| Animation flavour is the **default** one (not wave, not particle). | `integer/keyguard_charge_animation_type = 1`; `ChargeUtils.supportWaveChargeAnimation()` requires `== 2`, `supportParticleChargeAnimation()` requires `== 3`. |
| This device reports **shader charge animation support** but ships video resources. | `persist.sys.background_blur_supported = true` -> `ChargeUtils.SUPPORT_CHARGE_SHADER = true`. APK contains `res/raw/wired_charge_video.mp4`, `wired_quick_charge_video.mp4`, `single_charge.mp4`, `double_charge.mp4`, `single_to_double_charge.mp4`. |
| Display duration is 20 s with a 9.7 s timeout dismiss. | `getAnimationDuration()` returns `mShowChargingInNonLockscreen ? 10000 : 20000`; `MiuiChargeController` posts `mTimeoutDismissJob` at `mShowChargingInNonLockscreen ? 6100 : 9700`. |
| "Theme disables charge animation" is **not** a system setting. | `ChargeUtils.sChargeAnimationDisabled` is written only from the MAML lockscreen command `disableChargeAnim` (`LockScreenRoot.onCommand`) and reset in `MiuiNotificationPanelViewController`. |

Relevant classes (all in `classes3.dex`):

```
com.miui.charge.MiuiChargeManager
com.miui.charge.MiuiChargeController
com.miui.charge.ChargeUtils
com.miui.charge.container.MiuiChargeAnimationView   (extends FrameLayout)
com.miui.charge.container.MiuiChargeContainerView
com.miui.charge.container.MiuiChargeIconView
com.miui.charge.container.MiuiChargeLogoView
com.miui.charge.view.MiuiChargePercentCountView
com.miui.charge.view.MiuiChargeTurboView
com.miui.charge.view.NumberDrawView
com.miui.charge.shader.MiuiShaderChargeView
com.miui.charge.video.VideoChargeView
com.miui.charge.video.VideoView
com.miui.systemui.charge.MiuiBatteryStatus
```

### 1b. The second path: charging strong toast

Measured with `dumpsys battery unplug` / `dumpsys battery set ac 1` while the phone was unlocked:

```
I/MiuiChargeManager:   notifyBatteryStatusChanged: status 5 isPlugged 1 level 100 wireState 11 ...
I/MiuiChargeController: checkBatteryStatus: wireState 11 ... isChargeAnimationDisabled true
I/MIUIStrongToastControl: checkBatteryStatus: {status=5,plugged=1,level=100,wireState=11,...}
D/MIUIStrongToastControl: mWireState :-1  wireState :11, mChargeShowing :false
D/MIUIStrongToastControl: showChargeAnimation
D/MIUIStrongToastControl: mIsKeyguardShowing :false
D/MIUIStrongToastControl: init strong toast
D/MIUIStrongToast: showCustomStrongToast: {strongToastCategory=charge, duration=5000, chargeLevel=100.0, ...}
D/MIUIStrongToast: addView
I/WindowManager: wms.showSurfaceRobustly mWin:Window{... StrongToastView}
```

`com.miui.toast.MIUIStrongToastControl.onRefreshBatteryInfo` gates on
`mStateInitialized && !isKeyguardShowing()` and calls `showCustomStrongToast(bundle)` whenever
`wireState != -1` (i.e. plugged in). The bundle carries `strong_toast_category = "charge"` and
`duration = 5000` (10000 for wireless). A screenshot of it is at
`references/_scratch/rom_strongtoast.png`: a full width strip pinned to the top of the screen with
`Charging` on the left and `100%` + a bolt on the right, shown as a `StrongToastView` window.

So the two paths are:

| State when plugged in | ROM visual | Trigger |
| --- | --- | --- |
| keyguard showing | `MiuiChargeAnimationView`, full screen, added to `NotificationShadeWindowView` | `MiuiChargeController.showChargeAnimation` |
| unlocked | `StrongToastView`, top strip | `MIUIStrongToastControl.showCustomStrongToast(Bundle)` |

### 1c. `ChargeUtils.sChargeAnimationDisabled` is already `true` on this device

Every `checkBatteryStatus` log line ends with `isChargeAnimationDisabled true`. It is a plain
static, written only by the MAML lockscreen command `disableChargeAnim`
(`LockScreenRoot.onCommand`) and reset by `MiuiNotificationPanelViewController`. The device reports
`isDefaultTheme=0` and has a 2 MB custom `/data/system/theme/lockscreen`, so the **custom lockscreen
theme** turns the ROM's lockscreen animation off (and presumably draws its own). On a stock theme
this flag would be `false` and the `MiuiChargeAnimationView` path would be live, so the module must
handle both.

### 1d. Open question: the exact `showChargeAnimation` trigger

Read literally, `MiuiChargeController.checkBatteryStatus` calls `showChargeAnimation(i)` only in the
branch where the **new** wire state is `-1`, i.e. when the charger is *removed*:

```java
if (this.mWireState != i || z12) {
    ...
    if (i != -1) {                                  // plugged in
        this.mPendingChargeAnimation = false;
        handler.removeCallbacks(mScreenOffRunnable);
        dismissChargeAnimation("dealWithAnimationShow");
    } else if (zIsKeyguardShowing) {                // unplugged
        showChargeAnimation(i);
        ...haptic...
    }
}
```

This could not be exercised on hardware because `shouldShowChargeAnim()` short circuits to `false`
while `sChargeAnimationDisabled` is `true` and the keyguard is showing. It is **not** settled, and
it matters for one thing only: whether the ROM's `PowerManager.wakeUp(...)` on plug is something we
inherit for free or have to reimplement. Assume we have to do it ourselves.

## 2. Suppression strategy

Hook **`MiuiChargeAnimationView.addChargeView()` to a no-op** as the primary suppression point.
It is the last mile: whatever route the controller takes, the view never enters a window.

Second hook: **`MIUIStrongToastControl.showCustomStrongToast(Bundle)`**, skipping only when
`bundle.getString("strong_toast_category") == "charge"`. That class serves every strong toast
(charge, game mode, power saving, ...), so the category filter is mandatory, not optional.

Secondary belt-and-braces (optional, behind a setting): `MiuiChargeController.showChargeAnimation(int)`
no-op, so the ROM never even builds the view or grabs the wake lock.

Do **not** flip `ChargeUtils.sChargeAnimationDisabled`: it is read in five other places
(`MiuiChargeLogoView`, `MiuiChargeIconView`, `MiuiChargeAnimationView` x3) and changes layout
semantics, not just visibility.

## 3. Where our HUD is drawn

Our module runs inside the SystemUI process, so it inherits uid 1000 and its permissions.

* **Plan A (primary)**: own `WindowManager` window, `type = 2026` (the type the ROM's dead window
  branch would have used; a system-window type that sits above the keyguard).
  Flags: `NOT_FOCUSABLE | NOT_TOUCHABLE | LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS |
  SHOW_WHEN_LOCKED | HARDWARE_ACCELERATED`, `format = TRANSLUCENT`,
  `layoutInDisplayCutoutMode = ALWAYS`. Fully self-owned lifecycle.
* **Plan B (fallback)**: mirror the ROM and add the view to `NotificationShadeWindowView`, obtained
  through the same static stub container the ROM uses
  (`InterfacesImplManager.sClassContainer.get(CommonStub$registerCentralSurfaces$1.class)`
  -> `getNotificationShadeWindowView()`).

A normal app with `TYPE_APPLICATION_OVERLAY` **cannot** do this job: overlay windows are hidden by
the keyguard (`canBeHiddenByKeyguardLw`), and the charge animation happens precisely on the keyguard.

## 4. Battery data

Sysfs snapshot while plugged in at 100 %:

```
/sys/class/power_supply/battery/uevent
  POWER_SUPPLY_CHARGE_FULL=6200000         # uAh -> 6200 mAh
  POWER_SUPPLY_CHARGE_FULL_DESIGN=5500000  # uAh -> 5500 mAh, matches the K60's rated capacity
  POWER_SUPPLY_CHARGE_COUNTER=6200         # unit is suspicious, see below
  POWER_SUPPLY_VOLTAGE_NOW=4397000         # uV
  POWER_SUPPLY_POWER_NOW=10000000          # uW
  POWER_SUPPLY_CYCLE_COUNT=198
```

`charge_counter` and `charge_full` differ by exactly 1000x while the pack is at 100 %, which means
this kernel most likely reports `charge_counter` in **mAh** even though Android documents
`BATTERY_PROPERTY_CHARGE_COUNTER` as uAh. This has not been confirmed by a discharge test yet
(the device is on AC and cannot be unplugged by the agent).

**Decision**: derive the displayed value, and keep the raw values visible for later calibration.

```
full_mWh      = charge_full_uAh * nominalVolt * 1e-3
remaining_mWh = full_mWh * level / 100
```

## 5. Product decisions (confirmed with the owner)

1. **Trigger**: play on both plug and unplug, **including while unlocked**. Unplug uses the simplified
   one-shot pill animation from `zmd-charge`. Exposed as a setting.
2. **mWh**: derived value first; raw sysfs dumped on a debug screen; revisit after a discharge test.
3. **Screen wake**: replicate the ROM, i.e. `PowerManager.wakeUp` on plug while the screen is off.
4. **Docs**: findings recorded under `docs/investigations/`.

## 6. Visual spec source of truth

`references/zmd-charge` (Avalonia / C#) is the spec. Nothing about it is a black box:

| File | Content |
| --- | --- |
| `Styles/HudTheme.axaml` | all colours |
| `Styles/Geometries.axaml` | bolt geometry on a 24x24 grid |
| `Views/HudWindow.axaml` | full visual tree, sizes, column layout, opacities |
| `Animations/HudAnimations.cs` | 16 parallel tracks, per-segment cubic-bezier control points |
| `Views/HudWindow.axaml.cs` | badge arc geometry, unplug (simple) state, scale handling |

Key numbers:

```
pill           560 x 60 (state A/C)  <->  560 x 90 (state B); corner radius 30 <-> 18
global scale   0.8  (so 448 x 48/72 dp effective)
ripples        160 filled / 220 stroke 5.0 / 280 stroke 3.5, colour #656363
               rise from +16 px to 0; end scale 1.5 / 2.0 / 2.5; peak alpha .50/.50/.60
badge          46 circle, arc stroke 4.5 (start -90deg, sweep = 360 * pct, clamped .5..359.5)
               laptop screen 17 x 11.5 r1.5 border 2; base 24 x 3 r1.5; electrode 9 x 3, margin top -14
num row cols   32 | 18 | 14 | auto(Wh) | * | auto(%) | 14 | auto(badge) | 5
type           tagline 9/medium/ls2/alpha .40; title 26/bold/ls2; Wh 26/medium;
               WhMax 14/medium/alpha .55; pct 22/medium; '%' 13/medium/alpha .55
colours        pill #312F30  iconPlate #E9E7E4  bolt #141313  text #FFFFFF
               accent #C6CA4C  low #FF4D4F  badgeDark #262425
text           zh: "/// 超充模式" + "超充模式";  en: "/// SUPER CHARGE MODE" + "Super Charge Mode"
               unplug/saver variants exist for power-save mode
```

Timeline (6 s baseline, cues are fractions of the total; the intro segment keeps absolute duration
and the hold segment stretches with `DisplayDurationSeconds`):

```
0.04-0.10  bolt pops in alone (scale back-out)
0.07-0.09  pill background appears (scale .6->1, alpha 0->1)
0.09-0.12  A->B: height 60->90, corner 30->18
0.12-0.20  bolt slides centre -> left B slot; ripples expand from 0 (3 rings, same curve)
0.20-0.25  title fades in, centred
0.25-0.30  hold state B
0.30-0.36  B->C: title+ripples fade, height 90->60, corner 18->30, bolt slides to far left
0.38-0.42  numbers fade in
0.42-0.86  hold state C
0.86-0.89  whole thing scales to 0
```

**Easing**: Avalonia `KeySpline(x1,y1,x2,y2)` has exactly the same semantics as Android
`PathInterpolator`, including the `BackOut` overshoot (y2 = 1.275). The timeline can be ported
segment by segment, without approximation.

### Two deliberate deviations

1. **Scale.** 560 dp x 0.8 = 448 dp is wider than the 411 dp screen. Everything is scaled
   proportionally by `screenWidthDp * ratio / 560` (ratio default 0.92 -> ~0.676 on the K60).
2. **Font.** `zmd-charge` relies on system-installed Inter / HarmonyOS Sans and bundles no fonts.
   Android needs Inter bundled for exact Latin/numeric fidelity (OFL, license file required);
   CJK falls back to the system face (MiSans on HyperOS, OPPO Sans on OxygenOS).

## 7. Second device (OnePlus 15, OxygenOS 16, CPH2747)

Not investigated on hardware yet. Starting points harvested from `references/LuckyTool`:

```
com.oplus.charge.view.ChargeLevelAndLogoView
com.oplus.charge.view.FrameChargeLevelAndLogoView
com.oplus.charge.viewmodel.OplusChargeAnimImpl
com.oplus.charge.util.ChargeUtil
com.oplus.systemui.keyguard.charginganim.siphonanim.viewmodel.OplusChargeAnimFlavorOneImpl
com.oplus.systemui.keyguard.charginganim.siphonanim.view.ChargeLevelAndLogoFlavorOneView
feature flag: com.android.systemui.support_fullscreen_charge_anim
```

Architecture requirement: the core (timeline, drawing, window, plug detection) must stay ROM
agnostic behind a `RomAdapter` interface that only exposes suppression (and optionally a battery
status provider). Plug detection should use our own `ACTION_BATTERY_CHANGED` receiver registered on
the SystemUI context, so the adapter only has to know how to suppress.

## 8. Build environment (verified)

| Item | Value |
| --- | --- |
| Vector (LSPosed fork) | 2.2 Canary Debug, API 102, enabled, `com.android.systemui` in scope |
| libxposed API | `io.github.libxposed:api:102.0.0` (AAR, Maven Central reachable) |
| Module metadata | `src/main/resources/META-INF/xposed/{module.prop, java_init.list, scope.list}` |
| Gradle / JDK | Gradle 8.10.2, JDK 21 |
| AGP / SDK | AGP 8.9.1, platform android-35 and android-36 present |

## 9. Risks

1. `charge_counter` unit is unverified. Mitigated by deriving from `charge_full` x level.
2. z-order of window type 2026 against keyguard and AOD must be verified on hardware; plan B exists.
3. The AOD (`com.miui.aod`) may have its own charging presentation that can overlap ours.
4. Transform composition fidelity: Avalonia composes `TransformGroup { Scale, Translate }` in a
   specific order around `RenderTransformOrigin = 50%,50%`; each track must be checked, this is the
   most likely source of visual drift.

## Reproducing

```powershell
$s = '<device-serial>'                       # never commit the serial
adb -s $s pull /system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk references/_scratch/
E:\WorkBench\Utils\jadx\bin\jadx.bat -j 8 --no-res --show-bad-code `
    -d references/_scratch/jadx_sysui references/_scratch/MiuiSystemUI.apk
# charge animation sources:
#   references/_scratch/jadx_sysui/sources/com/miui/charge/
# resource values:
E:\WorkBench\Android\Sdk\build-tools\37.0.0\aapt2.exe dump resources references/_scratch/MiuiSystemUI.apk
```
