# Verity 扩展增强

**中文** | [English](README_EN.md)

为 [Verity JE](https://modrinth.com/mod/verity-je-official) 增加玩法与体验层的 Forge 扩展模组：
骑乘与站头互动、AI 建造、插件系统、人设与记忆、语音输入输出、MMD 骨骼动画等。

> 这是 Verity 的**扩展模组**，不能脱离原版 Verity 单独运行。

- Minecraft **1.20.1**
- Forge **47.4.21**（47.4.20 及以上）
- Java **17+**

---

## 主要功能

**互动**

- **骑乘**：潜行 + 空手右键骑到球形态的 Verity 身上
- **站头**：快捷键（默认 H）让她站到你头上，跟随移动与视角
- **抚摸**：与原版 PatPat 兼容，支持冷却与多人归属判定
- **喂食成长**：可选的体型成长，退出重进后保留

**AI 能力**

- **AI 建造**：用一句话描述，由模型设计轮廓、楼层与房间，再生成完整结构并分批安全施工。
  支持施工前预览、进度显示、取消与撤销、本地蓝图库、跨重启续建
- **插件系统**：用自然语言生成规则，例如
  `/verity-plugin create 挖到钻石的时候提醒我，并统计一共挖了多少`
- **主动感知**：进入世界、低生命值、死亡、睡眠等事件可主动触发对话
- **联网搜索**：可选接入搜索服务，让模型查到实时信息
- **摄像头视觉**：可把摄像头画面送入对话（手机端可用于「看看我面前是什么」）

**语音**

- **语音输入**：在线识别，或桌面端 Vosk 中文离线识别
- **语音朗读**：三种方式可选（交还原版朗读 / 本地离线模型 / 网络端点），
  支持 18 家语音服务商与音色克隆
- **双语字幕**：台词可以「写一种语言、念另一种语言」，并显示双语字幕

**外观**

- **MMD 动画**：把 VMD 动作、镜头与音乐组织成舞蹈项目，让 Demon Mode 同步起舞
- **自定义皮肤**：可替换皮肤贴图，第一人称手持同步生效
- **半透明界面**：世界里的菜单透出世界，主菜单自动走原版背景

**多人**

- 多人对话通过 `/v` 服务器命令触发，继续走原版 Verity 的提示词、记忆与工具链
- 服务端提供管理员命令，用于禁言、世界状态与事件控制

完整变更历史见 [`CHANGELOG.md`](CHANGELOG.md)。

---

## 安装

1. 安装 Minecraft Java Edition **1.20.1** 与 **Forge 47.4.21**（或更高的 47.4.x）。
2. 安装**原版 Verity**（[Modrinth](https://modrinth.com/mod/verity-je-official)），
   以及该 Verity 版本要求的依赖（如 GeckoLib、Cloth Config）。
3. 从 [Releases](https://github.com/hanchen68/verity-extended/releases) 下载本模组的 jar，
   放进游戏实例的 `mods` 文件夹。
4. 启动游戏，在 **模组 → Verity 扩展增强 → Config** 中配置。

> 安装新版本前请先删除旧的 jar。**同一个 modId 只允许一个 jar 存在**，
> 同时放两个会被 Forge 判定为重复并拒绝启动。
> 只想临时停用旧版时，把文件名后缀改成 `.disabled` 即可。

多人服务器建议客户端与服务端都安装，以获得完整功能。

---

## 配置

配置界面分为：状态提示、AI 对话、语音识别、语音合成、皮肤、事件感知、
高级设置、调试等分区。保存后会自动检查对话与语音链路，并在界面上给出结果。

主要配置项：

- **对话**：模型服务商、地址、密钥、模型名
- **语音**：识别与朗读各自的启用开关、服务商、音色
- **互动**：骑乘开关、是否仅主人可骑、站头开关
- **感知**：各类事件的触发开关与冷却

改配置后建议看一遍保存结果：连接检查失败会明确标出是哪一环出了问题。

---

## 兼容性

| 项目 | 说明 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.20 及以上 |
| 运行环境 | 客户端与服务端均可安装 |
| 必需 | 原版 Verity |
| 可选 | Cloth Config、PatPat（缺失时相关功能自动跳过） |
| 移动端 | Android / FCL 可用；不支持 Vosk 离线识别，建议使用在线语音 |

对 Verity 的**部分功能适配带有版本门控**：只有在检测到对应结构时才启用，
因此换用不同 Verity 构建不会导致专项适配错误生效。

---

## 常见问题

**装上之后进不去游戏 / 加载阶段退出**
先确认 `mods` 里没有两个 modId 相同的 jar。最常见的是同时放了两个版本的 Verity，
或忘记删除旧版的本模组。

**聊天栏显示一整段 JSON**
通常是模型返回了带额外字段的回复。请附上日志反馈。

**语音没声音**
确认「语音合成」已启用，且朗读路线与所选服务商一致。选了端点却填错密钥时不会静默改用其他方式。

**手机上录音或播放不正常**
录音与播放能力取决于启动器权限与设备音频驱动，建议改用在线语音。

遇到其他报错时，可按日志关键字在 [`docs/troubleshooting.md`](docs/troubleshooting.md) 里定位：
该文档整理了 9 类常见报错（无响应、504 超时、401 鉴权、TTS 404、STT 录音设备、
专用服务端 invalid dist、登录鉴权、Mixin 与 Forge 警告等），每条都给出
**日志关键字 → 根因 → 解决方案 → 验证方式**。

---

## 隐私

- **不会**在后台收集或上传你的游戏数据。
- 启用**在线对话**时，对话内容与必要的上下文会发送到你所配置的模型服务商；
  启用**在线语音识别**时会发送录音；启用**在线语音朗读**时会发送需要朗读的文本。
  这些都只发往你自己选择并配置的服务商。
- 关闭对应开关后不会发起相关网络请求。
- **请保管好自己的 API 密钥**，不要在截图、视频或日志中公开。

---

## 从源码构建

### 1. 准备第三方依赖

本仓库不包含第三方产物，需要自行准备：

| 路径 | 是什么 | 从哪来 |
|---|---|---|
| `src/main/java/com/hanchen/veritycustomapi/verity-6.0.0-beta.9/` | Verity JE 的类文件（编译时要对着它的 API） | Verity JE 的发行包 |
| `src/main/java/com/hanchen/veritycustomapi/PatPat-1.3.1+1.20.1+forge/` | PatPat 的类文件 | PatPat 的发行包 |
| `lib/geckolib-forge-1.20.1-4.8.4.jar` | GeckoLib 4.8.4 | GeckoLib 官方发布 |

前两项在 `build.gradle` 里以 `compileOnly` / `runtimeOnly` 引用，**路径需与上表一致**；
GeckoLib 放进 `lib/` 即可。

> 这两个模组没有公开的 Maven 仓库，所以走本地文件依赖。

### 2. 构建

```bash
./gradlew --no-daemon --max-workers=1 clean test productionJar
```

产物：`build/libs/Verity扩展增强-<版本>.jar`

### 3. 自检

```bash
./gradlew --no-daemon --max-workers=1 test
```

`build.gradle` 里还注册了一批针对本项目历史问题的回归检查，完整列表见该文件。
改配置项、加资源或动 Mixin 之后建议跑一遍。

---

## 协议

本项目采用 **MIT License**，见 [`LICENSE`](LICENSE)。

MIT 只覆盖本项目自己的代码与资源。随包分发的第三方组件（Vosk、ONNX Runtime、
soundlibs 音频库等）各自保留原许可证，不受 MIT 影响；完整清单见
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)，许可证正文见 `LICENSES/`。

本项目与 Mojang、Microsoft、Minecraft Forge、Verity 项目均无隶属或背书关系。

## 链接

- 下载：[GitHub Releases](https://github.com/hanchen68/verity-extended/releases)
- 本模组官网：https://verity.xrstudio.cn/
- 原版 Verity：https://modrinth.com/mod/verity-je-official
- 问题反馈：[GitHub Issues](https://github.com/hanchen68/verity-extended/issues)
- B 站：https://space.bilibili.com/3493111946807804
- 反馈群（QQ）：`1104727023`

开发：星绒工作室
