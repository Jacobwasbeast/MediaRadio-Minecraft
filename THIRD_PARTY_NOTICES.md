# Third-Party Notices

This project uses third-party software and external resources for media playback.

These notices are provided for attribution and compliance tracking.

## 1) Lavaplayer

- Component: `dev.arbjerg:lavaplayer:2.2.6` (and transitive `dev.arbjerg:lava-common:2.2.6`)
- Upstream: [https://github.com/sedmelluq/lavaplayer](https://github.com/sedmelluq/lavaplayer)
- Purpose in this project: Audio track loading/decoding pipeline
- Distribution model in this project: Shaded into mod artifact (classes/resources as configured in Gradle)
- License: See upstream repository license and notices

## 2) Lavalink YouTube Source

- Component: `dev.lavalink.youtube:v2:1.17.0`
- Upstream: [https://github.com/lavalink-devs/youtube-source](https://github.com/lavalink-devs/youtube-source)
- Purpose in this project: YouTube source manager integration for Lavaplayer
- Distribution model in this project: Shaded into mod artifact (classes/resources as configured in Gradle)
- License: See upstream repository license and notices

## 3) External Lavaplayer Native Binary Source (Runtime Download)

- Source link used by code:
  - Release tag: [https://github.com/Jacobwasbeast/MediaRadio-Natives-Repo/releases/tag/2.2.6](https://github.com/Jacobwasbeast/MediaRadio-Natives-Repo/releases/tag/2.2.6)
  - Release index: [https://github.com/Jacobwasbeast/MediaRadio-Natives-Repo/releases/download/2.2.6/index.json](https://github.com/Jacobwasbeast/MediaRadio-Natives-Repo/releases/download/2.2.6/index.json)
  - Repository: [https://github.com/Jacobwasbeast/MediaRadio-Natives-Repo](https://github.com/Jacobwasbeast/MediaRadio-Natives-Repo)
- Purpose in this project: Runtime retrieval of platform-native Lavaplayer binaries used by connector/native decoding paths
- Distribution model in this project: Not bundled in the main mod jar; downloaded at runtime to local storage
- License: Must follow license terms from the source repository and any included native components

## 4) Compliance Notes

- Keep this file updated when versions, sources, or distribution mode changes.
- If you redistribute jars or native binaries, include the required upstream license texts and notices in your release package.
- If an upstream project changes licensing terms, re-validate distribution rights before release.
- This file is an engineering notice document and is not legal advice.
