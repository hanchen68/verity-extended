# Verity Extended

[中文](README.md) | **English**

An add-on for [Verity JE](https://modrinth.com/mod/verity-je-official) that layers gameplay
and quality-of-life features on top of it: riding and head-perching, AI building, proactive
system, persona and memory, voice input/output, MMD skeletal animation, and more.

> This is an **add-on** for Verity. It cannot run without the original Verity installed.

- Minecraft **1.20.1**
- Forge **47.3+** (47.3.x and 47.4.x both work)
- Java **17+**

---

## Features

**Interaction**

- **Riding** — sneak and right-click with an empty hand to ride Verity in her sphere form
- **Head perch** — press the hotkey (H by default) to have her stand on your head and follow you
- **Petting** — compatible with the original PatPat, with cooldowns and per-player ownership
- **Feeding & growth** — optional body-size growth that persists across sessions

**AI capabilities**

- **AI building** — describe it in one sentence. The model designs the outline, floors and rooms,
  then the mod generates the full structure and builds it safely in batches.
  Includes a pre-build ghost preview, progress display, cancel and undo, a local blueprint
  library, and resuming across restarts
- **Proactive behaviour** — wanders nearby when idle, offers a heal when your health drops below half,
  and fights back when you are attacked
- **Proactive awareness** — events such as entering a world, low health, death and sleeping can
  start a conversation on their own
- **Web search** — optionally connect a search service so the model can look up current information
- **Camera vision** — feed a camera frame into the conversation (handy on mobile: "look at what's in front of me")

**Voice**

- **Speech input** — online recognition, or offline Chinese recognition with Vosk on desktop
- **Speech output** — three selectable backends (hand it back to vanilla / a local offline model /
  a network endpoint), with support for 18 TTS providers and voice cloning
- **Bilingual subtitles** — lines can be written in one language and spoken in another,
  with subtitles shown in both

**Visuals**

- **MMD animation** — organise VMD motions, camera and music into a dance project and have
  Demon Mode dance along to it
- **Custom skins** — swap the skin texture, with the first-person viewmodel keeping in sync
- **Translucent UI** — in-world menus let the world show through; the main menu falls back to
  the vanilla background

**Multiplayer**

- Multiplayer conversation is triggered by the `/v` server command and keeps using Verity's own
  prompt, memory and tool chain
- The server side provides admin commands for muting, world state and event control

See [`CHANGELOG.md`](CHANGELOG.md) for the full version history (in Chinese).

---

## Installation

1. Install Minecraft Java Edition **1.20.1** and **Forge 47.3+** (47.3.x or 47.4.x).
2. Install **the original Verity** ([Modrinth](https://modrinth.com/mod/verity-je-official)) plus
   whatever dependencies that Verity version requires (GeckoLib, Cloth Config, and so on).
3. Download this mod's jar from [Releases](https://github.com/hanchen68/verity-extended/releases)
   and drop it into your instance's `mods` folder.
4. Launch the game and configure it under **Mods → Verity 扩展增强 → Config**.

> Delete the old jar before installing a new version. **Only one jar with this mod id may exist** —
> placing two will be treated as a duplicate and Forge will refuse to start.
> To temporarily disable an older build, rename it with a `.disabled` suffix.

On multiplayer servers, installing this mod on both client and server gives the full feature set.

---

## Configuration

The config screen is split into sections: status, AI chat, speech recognition, speech synthesis,
skins, awareness, advanced and debug. Saving runs a check of the chat and voice paths and reports
the result in the UI.

Main settings:

- **Chat** — provider, endpoint, API key, model name
- **Voice** — separate enable switches, provider and voice for recognition and for speech output
- **Interaction** — riding toggle, owner-only riding, head-perch toggle
- **Awareness** — per-event triggers and cooldowns

After changing settings, look at the save result: if a connection check fails it points at exactly
which step went wrong.

---

## Compatibility

| | |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.3 and above (compiled against 47.3.0) |
| Environment | Can be installed on both client and server |
| Required | The original Verity |
| Optional | Cloth Config, PatPat (the affected features are skipped when absent) |
| Mobile | Works on Android / FCL; Vosk offline recognition is not supported — use online speech |

Some adapters that hook into Verity's internals are **version-gated**: they only activate when the
expected structure is detected, so switching to a different Verity build cannot make an adapter
apply to the wrong version.

---

## Troubleshooting

**The game won't start, or exits during loading**
First check that `mods` does not contain two jars with the same mod id. The most common causes are
having two versions of Verity installed, or forgetting to delete an older build of this mod.

**The chat shows a whole JSON blob**
Usually the model returned a reply with extra fields. Please report it with your log attached.

**No voice output**
Make sure speech synthesis is enabled and that the output backend matches the provider you
configured. If you picked an endpoint but the API key is wrong, it will not quietly fall back to
another backend.

**Recording or playback misbehaves on mobile**
Both depend on launcher permissions and the device's audio drivers. Using online speech is recommended.

For other errors, look them up by log keyword in [`docs/troubleshooting.md`](docs/troubleshooting.md).
It covers 9 common failures (no response, 504 timeouts, 401 auth, TTS 404, STT recording devices,
dedicated-server `invalid dist`, login auth, Mixin and Forge warnings), each written as
**log keyword → root cause → fix → how to verify**.

---

## Privacy

- This mod does **not** collect or upload your game data in the background.
- With **online chat** enabled, the conversation and the context it needs are sent to the model
  provider you configured. With **online speech recognition**, audio recordings are sent.
  With **online speech output**, the text to be spoken is sent. All of it goes only to providers
  you chose and configured yourself.
- With the relevant switches off, no such network requests are made.
- **Keep your API keys safe** — don't expose them in screenshots, videos or logs.

---

## Building from source

### 1. Prepare the third-party dependencies

This repository does not include third-party artifacts; you supply them yourself:

| Path | What | Where from |
|---|---|---|
| `src/main/java/com/hanchen/veritycustomapi/verity-6.0.0-beta.9/` | Verity JE class files (needed to compile against its API) | A Verity JE distribution |
| `src/main/java/com/hanchen/veritycustomapi/PatPat-1.3.1+1.20.1+forge/` | PatPat class files | A PatPat distribution |
| `lib/geckolib-forge-1.20.1-4.8.4.jar` | GeckoLib 4.8.4 | GeckoLib releases |

The first two are referenced from `build.gradle` as `compileOnly` / `runtimeOnly`, so
**the paths must match the table above**; GeckoLib just needs to sit in `lib/`.

> Neither of those mods publishes to a public Maven repository, so these are local file dependencies.

### 2. Build

```bash
./gradlew --no-daemon --max-workers=1 clean test productionJar
```

Output: `build/libs/Verity扩展增强-<version>.jar`

### 3. Self-checks

```bash
./gradlew --no-daemon --max-workers=1 test
```

`build.gradle` also registers a set of regression checks aimed at this project's own history;
see that file for the full list. Worth running after changing config keys, resources or Mixins.

---

## License

This project is released under the **MIT License** — see [`LICENSE`](LICENSE).

MIT covers only this project's own code and assets. Third-party components shipped inside the jar
(Vosk, ONNX Runtime, the soundlibs audio libraries and others) keep their own licenses, which MIT
does not affect. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for the full list and
`LICENSES/` for the license texts.

This project is not affiliated with or endorsed by Mojang, Microsoft, Minecraft Forge, or the
Verity project.

## Links

- Download: [GitHub Releases](https://github.com/hanchen68/verity-extended/releases)
- Project website: https://verity.xrstudio.cn/
- Original Verity: https://modrinth.com/mod/verity-je-official
- Bug reports: [GitHub Issues](https://github.com/hanchen68/verity-extended/issues)
- Bilibili: https://space.bilibili.com/3493111946807804
- Feedback group (QQ): `1104727023`

Developed by 星绒工作室 (Xingrong Studio)
