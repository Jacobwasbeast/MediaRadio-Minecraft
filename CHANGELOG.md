# Changelog

## 1.3.0

- Reworked playback/session synchronization across handheld, block, and contraption contexts.
- Improved queue and runtime authority handling to reduce desync, stale state overwrite, and unexpected pauses.
- Fixed multiplayer radio control flow so other players can modify placed radio playback/queue/options.
- Improved block UI/session display consistency for same-id simultaneous radios.
- Added additional fixes to cross-client playback stability and radio interaction behavior.

## 1.2.0

- Fixed Create contraption playback distance limits so audio is no longer global.
- Fixed held item transform compatibility issues in larger modpacks.
- Added server-side playback simulation for radios without an active owner.
- Fixed UI library text alignment and textbox positioning issues.
- Added configurable client options for Media Radio settings placement and behavior.
- Fixed HUD media stats clipping and improved overlay sizing for longer content.

## 1.1.0

- Added Create-focused playback support improvements and contraption audio behavior fixes.
- Added inventory-sharing workflow improvements for handheld playback context.
- Added client playback configuration controls.
- Fixed multiple networking synchronization issues affecting remote clients.
- Fixed loop handling edge cases.
- Fixed several rendering issues, including contraption/overlay alignment and occlusion behavior.
- Fixed radio GUI facing/orientation behavior.
- Updated placeholder assets for antenna and display material items.

## 1.0.0

- Initial public baseline for Media Radio.
- Added single radio item with handheld and place mode support.
- Added placeable radio block with model-aligned hitbox and directional playback.
- Added media overlay rendering for held and placed radios.
- Added queue controls, seeking, volume, shuffle, and loop behavior.
- Added library + playlist tabs and playlist import flows.
- Added thumbnail loading/display across UI and overlays.
- Added radio identity/state handling for handheld and placed radios.
- Added crafting materials (`Antena`, `Display`) and recipes for all new items.
- Set project license to MIT.
