# Third-Party Notices

Verity Extended is released under the MIT License (see `LICENSE`). That license
covers only the original code and assets of this project. Third-party software
listed below keeps its own license, and MIT does not replace or restrict it.

## Distributed inside the production JAR

### Vosk API 0.3.45

- Copyright: Alpha Cephei Inc. and Vosk contributors
- License: Apache License 2.0
- Project: https://github.com/alphacep/vosk-api
- License file: `LICENSES/Vosk-APACHE-2.0.txt`

The production JAR contains Vosk Java classes and native libraries. Vosk did
not provide a separate NOTICE file at the audited `v0.3.45` source tag.

### Microsoft ONNX Runtime 1.16.3

- Copyright: Microsoft Corporation
- License: MIT License
- Project: https://github.com/microsoft/onnxruntime
- License file: `LICENSES/ONNX-Runtime-MIT.txt`

The production JAR contains ONNX Runtime Java classes and native libraries.
ONNX Runtime's original `ThirdPartyNotices.txt` is also preserved in the JAR.

### Java Sound 音频解码库（soundlibs 系列）

生产 JAR 内含下面五个库，作用是让 MMD 能通过 `AudioSystem` 解码 MP3 / OGG。

| 库 | 版本 | 上游项目 |
|---|---|---|
| jlayer | 1.0.1.4 | http://www.javazoom.net/javalayer/javalayer.html |
| mp3spi | 1.9.5.4 | http://www.javazoom.net/mp3spi/mp3spi.html |
| vorbisspi | 1.0.3.3 | http://www.javazoom.net/vorbisspi/about.html |
| tritonus-share | 0.3.7.4 | http://www.bomers.de |
| jorbis | 0.0.17.4 | http://www.jcraft.com/contact.html |

- 许可证：GNU Lesser General Public License 2.1（由 `com.googlecode.soundlibs` 的父 POM
  `oss-parent` 声明，各子模块未另行声明）
- 许可证正文：`LICENSES/LGPL-2.1.txt`
- 打包坐标：`com.googlecode.soundlibs:{jlayer, mp3spi, vorbisspi, tritonus-share, jorbis}`

**关于重定位（属于修改，按 LGPL-2.1 第 6 条声明）**：这些库的包名在打包时被机械重写，
例如 `org.tritonus.share` 变成 `com.hanchen.veritycustomapi.shaded.org.tritonus.share`。
原因是多个 Mod 打包同一套 soundlibs 会让 JPMS 模块解析失败
（`java.lang.module.ResolutionException`，实测撞过原版 Verity 的 genai bundle）。
**除此之外未做任何改动**，源码可从上述上游项目取得；按 LGPL-2.1，这些被修改的副本
同样以 LGPL-2.1 提供。

## Runtime dependencies not distributed as part of this mod

### Minecraft Forge

- License: GNU Lesser General Public License 2.1 with Forge project notices
- Project: https://github.com/MinecraftForge/MinecraftForge
- License file: `LICENSE.txt`

The root `LICENSE.txt` is the license supplied with the Forge MDK. It applies
to Forge material and does not license the original Verity Extended code.

### Minecraft

Minecraft is a trademark of Microsoft Corporation. Minecraft and its assets
are governed by the applicable Microsoft and Mojang terms. This project is
not affiliated with or endorsed by Mojang Studios or Microsoft.

### Verity

Verity Extended is an extension for Verity. Verity must be installed
separately and remains subject to the license and terms chosen by its
copyright holder. This project does not grant rights to Verity code or assets.

### Cloth Config

Cloth Config is an optional runtime dependency and is not embedded in the
production JAR. It remains subject to its own license.

## No affiliation

This project is not affiliated with or endorsed by Mojang Studios, Microsoft,
Minecraft Forge, the Verity project, or the owners of any third-party service
used by the mod.

## Online services

Optional online features may communicate with services selected by the user
or supplied by the configured cloud account. Those services remain subject to
their own terms, privacy policies, availability, and pricing.

## Source references used for this notice

- Vosk 0.3.45 license: https://github.com/alphacep/vosk-api/blob/v0.3.45/COPYING
- ONNX Runtime 1.16.3 license: https://github.com/microsoft/onnxruntime/blob/v1.16.3/LICENSE
- ONNX Runtime notices: https://github.com/microsoft/onnxruntime/blob/v1.16.3/ThirdPartyNotices.txt

If a notice in this document conflicts with an included third-party license,
the included third-party license controls.
