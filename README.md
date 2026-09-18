# EndfieldCharge

一个将 ROM 的插拔充电动画替换为 Endfield 风格的超充模式 HUD 的 Xposed 模块（libxposed API 102）

视觉效果复刻自 [zmd-charge](https://github.com/QinAnze/zmd-charge)，快去给她点一颗 Star 吧！

Fully Powered by DeepSeek V4.1 Flash.

仅为以下机型及 ROM 特别适配且验证通过:

- Redmi K60 (`mondrian_eea`, HyperOS `OS2.0.208.0.VMNEUXM`, Android 15)
- OnePlus 15 (`CPH2747`, OxygenOS `16.0.9.400(EX01)`, Android 16)

仅作学习研究使用，不推荐尝试或传播，因此不会发布 Release。
由此导致的任何问题概不负责，刷机不规范，数据两行泪。

## Layout

```
app/src/main/java/com/lemoneko/endfieldcharge/
  ModuleEntry.kt              libxposed API 102 entry point
  core/                       ROM agnostic: window, battery, timeline, settings
    timeline/                 the ported animation, no Android imports where it matters
    settings/                 settings model, JSON codec, hook side of the channel
  rom/                        per-ROM adapters, one file per ROM
  ui/                         the Canvas renderer, HUD copy, fonts, settings screen
  debug/                      off screen frame renderer, sysfs probe
  settings/                   the provider and the app side of the channel
```

## Licence

The module is licensed under AGPL-3.0.
Inter font is bundled under the SIL Open Font License (`app/src/main/assets/fonts/Inter-OFL.txt`).
