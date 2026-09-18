# EndfieldCharge

An Xposed module that replaces the ROM's plug/unplug charging animation with the
Endfield-style super-charge HUD.

Visuals are a 1:1 port of [zmd-charge](https://github.com/QinAnze/zmd-charge), an Avalonia
desktop app that draws the same HUD on Windows. Everything about the animation - the pill geometry,
the colours, the 16 animation tracks, every cubic-bezier control point - comes from that project's
source, which is treated as the specification.

## What it does

HyperOS has **two** independent charging visuals, and which one you get depends on the keyguard:

| State when plugged in | ROM visual | How it is suppressed |
| --- | --- | --- |
| keyguard showing | `MiuiChargeAnimationView`, full screen, added to the notification shade root view | `addChargeView()` made a no-op |
| unlocked | `MIUIStrongToastControl`, a full width strip at the top | `showCustomStrongToast(Bundle)` dropped when `strong_toast_category == "charge"` |

OxygenOS 16 has three, and they are all different mechanisms:

| What | How it is suppressed |
| --- | --- |
| `OplusChargeAnimationView`, the full screen animation | `WindowManagerImpl.addView` dropped when the window title matches |
| `Wired Charging Animation` / `Charging Animation`, the stock AOSP ripples | same window title filter |
| the water wave ripple, a `SurfaceControlViewHost` on its own surface | `OplusChargeAnimController.onWaterWaveVFXStart()` made a no-op |
| the charging live alert capsule, a Seedling card from OPPO's Pantanal service | filtered out of the card list by service id in `DecisionHelper.filterStaticServiceListByEntrance` |

In their place the module draws its own HUD in a `WindowManager` window of type `2026`, the same
system window type the ROM's own (dead) window branch would have used, which is what lets it draw
above the keyguard. Plug and unplug are detected with the module's own `ACTION_BATTERY_CHANGED`
receiver, so the ROM-agnostic core does not depend on the ROM's own plumbing.

Turning the module off in the settings screen hands all of them straight back to the ROM, with no
SystemUI restart.

## Build and install

```bash
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then enable the module and pick `com.android.systemui` as its scope in the Xposed manager, and
restart SystemUI. With Vector, which is what the development device runs:

```bash
adb shell su -c '/data/adb/lspd/cli modules enable com.lemoneko.endfieldcharge'
adb shell su -c '/data/adb/lspd/cli scope set com.lemoneko.endfieldcharge com.android.systemui'
adb shell su -c 'killall com.android.systemui'
```

`META-INF/xposed/scope.list` already declares the scope, so most managers will offer it directly.

## Settings

The app is both the settings screen and a preview: it runs the exact same view and timeline in an
ordinary process, so the animation can be played, scrubbed and dumped without touching SystemUI.

| Setting | Default | Notes |
| --- | --- | --- |
| Replace the ROM animation | on | off hands the animation back to the ROM |
| Top offset | 8 dp | below the display cutout; the K60's cutout is 34 dp tall and centred |
| Width | 92 % of screen | 560 design units x 0.8 is 448 dp, wider than a 411 dp screen |
| Duration | 6.0 s | 3 to 10; the intro segment keeps its absolute 2.52 s |
| Bounce | 0.275 | overshoot of the BackOut spline |
| Ripple intensity / spread | 1.0 | |
| Language | auto | the reference follows the system locale |
| Wake the screen on plug | on | replicates the ROM's `PowerManager.wakeUp` |
| Play on unplug | on | the simplified one-shot timeline |

Settings are delivered to SystemUI by a `ContentProvider` owned by the module app, not by libxposed
remote preferences: those need the framework to push a binder into the module app, and that push is
performed by the framework's manager app, which is not installed on the development device. See
`docs/investigations/01-feasibility.md`.

## Debug affordances

```bash
# render one PNG per cue, off screen, for pixel comparison
adb shell am start -n com.lemoneko.endfieldcharge/.ui.MainActivity --ez renderSheets true
adb pull /sdcard/Android/data/com.lemoneko.endfieldcharge/files/frames

# open the preview on a specific frame, or the unplug timeline
adb shell am start -n com.lemoneko.endfieldcharge/.ui.MainActivity --ei cue 250
adb shell am start -n com.lemoneko.endfieldcharge/.ui.MainActivity --ez unplug true

# read or write the settings provider without the UI
adb shell sh /data/local/tmp/probe_settings.sh '<json>'
```

`tools/capture-reference-hud.ps1` captures the reference app frame by frame for A/B comparison.
