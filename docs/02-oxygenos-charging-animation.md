# 02 · OnePlus 15 (OxygenOS 16) charging animation

Status: adapter implemented and verified on hardware for the AOSP wired ripple; the OPPO
full-screen animation is implemented but not yet exercised (see [Open items](#open-items)).

## Device facts

| | |
| --- | --- |
| Model | OnePlus 15, `CPH2747` (device `OP611FL1`) |
| ROM | OxygenOS 16, `CPH2747_16.0.9.400(EX01)` |
| Android | 16, SDK 36 |
| Display | 1272x2772 @ 560 dpi, so about 363 x 792 dp |
| Cutout | `Rect(0, 141 - 0, 0)`, 141 px = about 40 dp, centred |
| SystemUI | `/system_ext/priv-app/SystemUI/SystemUI.apk` (108 MB) |
| Xposed | LSPosed IT `v2.2.0-it (7888)`, libxposed API 102 |

Unlike the K60, this device has no LSPosed manager app installed. `/data/adb/modules/zygisk_lsposed/lspctl`
exists, but its `module` and `scope` subcommands are **read-only** (`list` / `show` only), so
activating a module still needs the user.

## Where the animation lives

The K60's reference, `references/LuckyTool`, names a `siphonanim` package
(`com.oplus.systemui.keyguard.charginganim.siphonanim.*`) that does **not** exist in this build. The
classes here are:

```
com.oplus.charge.viewmodel.OplusChargeAnimImpl        # builds the view, adds it to the window
com.oplus.charge.viewmodel.OplusChargeAnimController  # state machine fan-out to listeners
com.oplus.systemui.keyguard.charginganim.ChargingAnimationImpl
com.oplus.charge.view.ChargeLevelAndLogoView
com.oplus.charge.view.FrameChargeLevelAndLogoView
com.oplus.charge.util.ChargeUtil
```

The charge animation is **not** a separate view class that can be no-op'd the way HyperOS's
`addChargeView` can. `OplusChargeAnimImpl.updateChargeAnimState(int, String)` inflates the layout and
adds it inline (state 1 = create, 2 = resume, 4 = cancel), and Kotlin inlining means there is no
`createChargingAnimLayout` method to hook even though the trace label says there is.

OxygenOS additionally ships the stock AOSP animations. All four candidates title their windows:

| Title | Owner |
| --- | --- |
| `OplusChargeAnimationView` | `OplusChargeAnimImpl` main display |
| `OplusSubChargeAnimationView` | `OplusChargeAnimImpl` sub display |
| `Wired Charging Animation` | `com.android.systemui.charging.WiredChargingRippleController` |
| `Charging Animation` | `com.android.systemui.charging.WirelessChargingAnimation` |

## Suppression strategy

Hook **`android.view.WindowManagerImpl.addView(View, ViewGroup.LayoutParams)`** and drop the call
when `LayoutParams.getTitle()` is one of the four titles above.

That is a framework method rather than a ROM one, which is normally a smell, but it is the right
point here for a specific reason: `OplusChargeAnimImpl.removeMainChargeView()` and
`removeSubVFXView()` both check `WindowInspector.getGlobalWindowViews().contains(view)` before
calling `removeView`. A view that was never added is therefore a clean no-op on the way out, and
nothing throws. The ROM's state machine is left completely untouched - it still creates, drives and
later tears down its view, it just never becomes visible.

Hooking `updateChargeAnimState` instead would have been simpler to target and wrong: skipping
state 1 leaves `chargeAnimState` stale and the listeners never see the create transition.

The title filter is what keeps this narrow. `addView` is called for every window in SystemUI, so the
hook runs often, but it only ever touches these four titles.

## The water wave ripple is not a window

The first hardware test with a real charger and the keyguard showing produced a partial result: the
charge percentage disappeared, but a ripple distortion kept playing around it.

```
[oplus] suppressed charge window: OplusChargeAnimationView      <- the view is gone
```

The ripple is `OplusChargeAnimController.onWaterWaveVFXStart`, which loads
`ripple_update_density_001.coz` into OPPO's COE effect engine and hands it a `SurfaceControlViewHost`.
That is composited on its own surface, so the window title filter never sees it. It has to be
stopped at its own entry point, and only the start is blocked: `onWaterWaveVFXStop` and
`onWaterWaveVFXStopImmediately` are null-guarded and act on an effect that never started, so the
ROM's cleanup still runs.

```
[oplus] suppressed charge window: OplusChargeAnimationView
[oplus] suppressed water wave VFX
```

## The charging live alert

Separately from the animation, OxygenOS shows a **live alert** capsule while charging: a black pill
with green `Charging 80%` and a battery glyph. It only appears while unlocked, which matches the
card data's own `lockScreenShowHostMap: {"8": false}`.

It is not a notification and not one of the four windows above. It is a **Seedling card**, delivered
by OPPO's Pantanal decision service. Captured at runtime
(`references/_scratch/op15/capture_livealert.ps1`):

```
serviceId = 268451924   name = ChargeSeedling   hostName = com.oplus.battery
provider  = com.oplus.powermanager.chargeSeedling.ChargeSeedlingCardProvider
action    = pantanal.intent.business.app.system.CHARGE
```

and its payload decodes to exactly the visible element:

```json
{"chargeStatus":"Charging","chargeLevel":"80%","chargeColor":"#00BD13",
 "chargeViewBg":"assets/images/charge_card_bg.webp","sweepShow":"end-to-start"}
```

The pipeline is `com.oplus.pantanal.ums` (decides) -> `pantanal.decision.MultiInstanceDecisionAdapter`
inside SystemUI -> `com.oplus.systemui.plugins` (renders) -> a window titled `SeedlingCard`.

`SeedlingCard` is the title for **every** Pantanal card, so suppressing that window wholesale would
also kill unrelated live alerts. The card list is filtered by service id instead, in
`pantanal.decision.DecisionHelper.filterStaticServiceListByEntrance` - a plain static method taking
and returning a `List<ServiceInfo>`.

Those classes live in the `com.oplus.systemui.plugins` APK and are loaded through a plugin
classloader, so the hook cannot be installed up front. It waits for
`SeedlingPluginManager.onPluginConnected(SeedlingPlugin, Context)`, whose `Context` carries that
classloader, and installs the filter from there.

Verified on hardware, with the ROM still producing the card:

```
I/PantaCard.SysUi.MultiInstanceDecisionAdapter: receive new list ... [serviceId:268451924,...]
[oplus] suppressed charge live alert (1 card)
```

The same decision list also carries a `BatterySeedling` (`serviceId=268451902`, power save) which is
deliberately left alone, which is the point of filtering by id rather than by window title.

`CHARGE_SEEDLING_SERVICE_ID` is the one magic number in the adapter. If a ROM update changes it, the
filter silently stops matching; re-derive it with the capture script and look for
`name=ChargeSeedling` in `SG::SeedlingDiscover`.

## Trigger conditions

`ChargingAnimationImpl.isShouldResume()` requires `isKeyguardOrLauncherShowing()` and
`mDeviceProvisioned`, and the ROM computes a `keyguardCharging` flag of its own. Its log while the
phone is unlocked and plugged says it plainly:

```
ChargingAnimationImpl-->onAdditionalBatteryStateChanged:OplusAddBatteryStatus(
    plugged = 2, ..., chargerWattage = 0, ..., realCharging = false, keyguardCharging = false, ...)
OplusChargeAnimImpl-->updateChargeAnimState , state : cancel charge anim, reason : charging disconnect
```

so the OPPO full-screen animation does not run while unlocked, and `dumpsys battery` faking is not
enough to exercise it either: `realCharging` stays false because the ROM reads the real charger.
A physical charger with the keyguard showing is required, and that is how the two lines above were
produced.

The AOSP wired ripple has no such gate and does fire on plug. Measured with the module on and off:

```
module ON:  [oplus] suppressed charge window: Wired Charging Animation
            [hud] plugged in (level=80%)
            [window] HUD window attached (type=2026)

module OFF: [hud] plugged in (level=80%)
            (no suppression line, no HUD window)
```

## Logging gotcha

`android.util.Log` output from the hooked SystemUI process **never reaches logcat** on this ROM,
while the framework's own log does. The module looked completely dead until `HudLog` was also
routed into the Xposed log via `HudLog.attach`. Read module output with:

```bash
adb shell su -c 'sh /data/local/tmp/gl.sh'     # greps the newest modules_*.log for endfieldcharge
```

## Battery nodes

Read at 80 % while plugged, for comparison with the K60:

```
charge_full    7300000 uAh   (7300 mAh, matches the OP15's rated capacity)
charge_counter 4872000 uAh
voltage_now    4147000 V
```

On the K60 `charge_full / charge_counter` was exactly 1000 at 100 %, which suggested
`charge_counter` was really in mAh there. Here the two are the same order of magnitude, so the unit
genuinely differs between the two devices and the derived `fullMwh * level / 100` remains the only
value that is comparable across them.

## Status bar battery indicator

OxygenOS also shows a small green `80` battery chip in the status bar while charging. That is the
ordinary status bar battery, not a live alert and not an animation, and it is left alone.
