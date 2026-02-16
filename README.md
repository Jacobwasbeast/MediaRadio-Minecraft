# Media Radio

Media Radio is a multi-loader Minecraft mod for **1.20.1** that adds a functional radio item and placeable radio block with in-world playback controls.

## Features

- Single radio item with handheld and placement behavior
- Handheld playback
- Offhand use
- Block placement mode
- Placeable radio block with on-model media overlay (status/title/artist/time/volume/thumbnail)
- 3D positional audio for placed radios
- Queue controls (play, prev, next, remove, reorder)
- Shuffle and loop modes
- Playlist system with import flows (including YouTube/global/invites UI paths)
- Source management UI for adding media from multiple source types
- Thumbnails shown across now playing, queue, library, and overlays

## Controls

- `Shift + Right Click` with radio item: toggle handheld/place mode
- `Right Click` with radio item in handheld mode: open radio UI
- `Right Click` with radio item in place mode: place radio block
- `Shift + Right Click` on placed radio block: pick radio up

## Data Behavior

- Playlist data is global for the player profile.
- Queue/playback state is tied to each radio ID so different radios can keep separate queues/states.
- Picking up and placing a radio preserves the same radio identity/state flow.

## Source Notes

- Supports direct URLs/files and YouTube-driven flows in UI.
- YouTube behavior can change when upstream extractor/provider APIs change.

## Crafting

See `Recipe.md` for all current crafting patterns and ingredient keys.

## Development

### Build / Compile

Use your normal Gradle workflow for your platform and Java setup.
Compile all modules (`common`, `fabric`, `forge`) before release.

### Project Layout

- `common/`: shared gameplay, UI, audio, networking, rendering, data
- `fabric/`: Fabric entrypoints/integration
- `forge/`: Forge entrypoints/integration

## License

This project is licensed under the **MIT License**.
See `LICENSE` for full terms.
