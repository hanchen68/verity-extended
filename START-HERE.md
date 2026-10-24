# Verity 扩展增强 4.5.2 · 使用说明 + AI 接手提示词

这个文件是**独立单篇**，两用：

- 给**玩家**：看第一部分，装好就能用；
- 给**AI / 新接手的人**：把第二部分整段复制过去，作为开工提示词。

---

# 第一部分 · 使用说明

## 1. 安装

| 项 | 要求 |
|---|---|
| 游戏 | Minecraft 1.20.1 |
| 加载器 | Forge 47.4.20 以上（实测 47.4.21 / 47.4.23） |
| 必装 | Verity JE（实测 6.0.0-beta.9）、GeckoLib 4.4+ |
| 选装 | PatPat（摸头联动）、《The Shooting Star》（星之遥控器） |

把 `release/Verity扩展增强-4.5.2.jar` 放进 `mods/`。
同一个 mods 目录里**只能有一个** `Verity扩展增强-*.jar`，换版本先删旧的。

## 2. 第一次进游戏先做三件事

1. **填 API**：Mods → Verity 扩展增强 → Config → 「对话与记忆」，
   填自己的 OpenAI 兼容地址与密钥，或授权云端用内置线路；
2. **选语音**：「语音识别」「语音朗读」各选一种；没有离线模型就用在线，Android 会自动回退系统识别；
3. **试一句**：单人直接打字，多人按服务器要求用 `/v 内容`（私聊）或 `/v! 内容`（公开）。

改完点保存，会进「配置健康检查」页，能直接看出哪些生效、哪些被云端接管。

## 3. 三种说话方式

| 方式 | 操作 |
|---|---|
| 文字 | 单人直接打字；多人 `/v`、`/v!` |
| 语音 | 默认按 **V** 说，再按一次结束（按键可在按键设置改） |
| 快捷面板 | 按 **K**，「跟过来 / 到身边 / 提醒我」一键发 |

回复可分别开关：聊天栏、右下气泡、头顶字幕、语音朗读。

## 4. 日常互动

- **送礼**：潜行 + 丢弃（Q），准星对着她；每次 1 个，30 秒冷却，好感 +1；
- **喂食**：拿食物右键；
- **踢开**：潜行 + 左键（不造成伤害，10 秒冷却）；
- **站头 / 骑乘**：潜行 + 空手右键；
- **摸摸头**：需装 PatPat；
- **好感与成长**：每日上限默认 3，可在配置改，成长会影响体型与部分表现。

## 5. 常用命令

```text
/verity-ai follow | stop | come | goto <x> <y> <z> | remind <分钟> <事> | status
/verity-mmd list | play <项目> | stop | speed <倍数>
/verity-plugin create <一句话需求> | list | disable <名字>
/verity-admin status | list | cleanup | config        （多人服管理）
/verity-stellar list | cast <技能> [x y z] | hold <秒> （需装该模组）
```

星之遥控器要点：弹道从**她的位置**打出，中间有地形或生物会提前命中；
施法后聊天里会给出**实际落点**，偏了就是被挡了。
`/verity-stellar hold 10` 可以只看她举着遥控器、不放技能。

## 6. 说话「Q 弹」（默认关闭）

配置 → 「外观与资源」→「说话动作（Q 弹）」：开关 + 幅度 / 速度 / 横向挤压，
另有 **鬼畜模式**（幅度 ×1.6、速度 ×2.6，附甩头和原地弹跳）。只在她说话时生效。

## 7. 云端配置的本地优先

你在本地改过的项会被记住：云端再下发**同样的值**不会盖回去，只有云端把该键改成**新值**才重新接管。
云配置页显示「已有 N 项由本地接管」，点「全部交还」即恢复完全跟随云端。

## 8. 常见问题

| 现象 | 处理 |
|---|---|
| 提示「没检测到原版 Verity」 | 确认 Verity JE 已装且含 `VerityEntity` |
| 语音没反应 | 检查语音键（默认 V）、麦克风权限；Android 需系统有中文语音服务 |
| 没声音 | 「语音朗读」是否启用；PC 离线 TTS 需 ONNX 运行库，Android 只能在线 |
| 云端开关自己变回关闭 | 4.5.2 起有快照自动恢复；仍有就看日志有没有「被写坏了」 |
| 改的设置重启又变回去 | 该项被云端接管，云配置页可「全部交还」 |
| 星之遥控器没反应 | 确认装了该模组、背包有遥控器（配置可关掉该要求） |
| 启动崩溃提到 `.toml` | 删 `config/verity_extended-client.toml` 让游戏重建（密钥在独立文件，不受影响） |

---

# 第二部分 · 发给 AI 的接手提示词

> 用法：打开本项目文件夹，把下面整段贴给 AI（Claude / GPT / Codex 均可）。
> 它会让 AI 先读 `AGENTS.md` 与 `docs/`，再按交付规范干活。

```text
你现在接手一个 Minecraft 模组项目：**Verity 扩展增强 4.5.2**（modId `verity_extended`），
代码就在你当前打开的目录里。

【第一步：先读，不要先改】
按顺序读这四份，读完再动手：
1. AGENTS.md                —— 硬性约束、代码地图、任务索引
2. docs/01-架构总览.md       —— 启动顺序与三条主数据流
3. docs/03-Mixin与兼容层.md  —— 与原版 Verity 的耦合点（最容易踩坑的地方）
4. docs/09-交付与版本规范.md —— 交付流程与状态报告模板

需要更多细节时按需读 docs/02（目录）、04（配置）、05（网络/命令）、06（构建/排障）、07（扩展骨架）、08（已知限制）。

【第二步：本项目的事实】
- 环境：MC 1.20.1 / Forge 47.4.x / Java 17；依赖 Verity JE 6.0.0-beta.9 与 GeckoLib 4.4+。
- 本质：不重写 Verity，只在它的边界上加功能（AI 对话链路、云端配置、语音、好感、恐怖事件、MMD、插件、第三方兼容层）。
- 构建：首次需 `powershell -ExecutionPolicy Bypass -File tools/prepare-lib.ps1 -JarPath "<你的 verity jar>"`
  展开依赖，然后 `./gradlew compileJava`；发布用 `./gradlew clean productionJar`。
- 产物：`build/libs/Verity扩展增强-<version>.jar`，版本号在 build.gradle。

【第三步：必须遵守的硬约束】
1. 不要改 `src/main/java/.../verity-src原版`、`verity编译`、`verity-cn版编译`（反编译参考，已 exclude 出构建）。
2. 不要直接调 `COMMON_SPEC.save()` / `CLIENT_SPEC.save()`；一律走 `ConfigManager.saveCommonConfig()/saveClientConfig()`，
   否则云端覆盖值会被写进玩家 TOML。
3. 不许把 `cloud` 配置段开放给云端覆盖；`ai_extensions.webSearch*` 也保持本机管理。
4. common 层代码不许 import 客户端类；需要时用 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)` 或放到 `client/` 包。
5. 新增 mixin 必须同时在 `mixins.verity_extended.json` 登记；客户端类放 `client` 数组，否则专服启动即崩。
6. 对原版/第三方内部方法的注入优先 `require = 0`，并在方法内自行降级；依赖 lambda 名（如 `lambda$queryAI$4`）的必须容错。
7. 所有第三方耦合写进 `compat/`，用反射 + 熔断（`resolve()/failed`），缺类缺方法只降级、不抛异常。
8. 新增配置项必须写 comment、默认值取最保守，并在配置界面加上对应行。

【第四步：工作方式】
- 先定位再改：用搜索/阅读确认调用链，别凭猜测改代码；找不到就明说并问。
- 一次只做一件事，改完立即 `compileJava`；涉及配置/云端/mixin 时跑对口的自检任务
  （cloudOverridesTest、mixinPackageHygieneTest、verityStructureDetectorTest 等）。
- 改动跨 3 个以上文件时，先给我一句话计划再动手。
- 不要顺手重构无关代码，不要引入新的第三方依赖。

【第五步：交付时按 docs/09 输出】
1. 升 `build.gradle` 的 version（补丁位/次版本位/主版本位按规则判断）；
2. 在根 `CHANGELOG.md` 顶部写条目（固定结构：新增 / 修复 / 兼容 / 配置 / 已知限制 / 产物 + SHA-256）；
3. `clean productionJar`，算 SHA-256 回填 CHANGELOG 与 `release/SHA256.txt`；
4. 最后**原样输出**状态报告：

## 交付：v<版本>（<日期>）
**产物**：文件路径 / SHA-256 / 构建结果
**本版改动**：新增 / 修复 / 兼容
**配置变更**：新增键 / 变更键 / 移除键
**兼容性**：适配基准版本、注入点核对结果、网络协议是否变、依赖区间
**已知问题**：真实存在的结构性限制（不许写「暂无」）
**验证方式**：可照做的命令 + 需要观察的日志关键行

【现在开始】
先读那四份文档，然后用三句话向我复述：
(1) 这个项目在做什么；(2) 哪三条约束最容易违反；(3) 你打算怎么验证一次改动。
复述完等我给具体任务，不要自行开工。
```

---

## 附：最小提示词（只想快速丢一句时用）

```text
接手 verity_extended（MC 1.20.1 / Forge，Verity JE 扩展）。先读 AGENTS.md、docs/01、docs/03、docs/09，
遵守其中的硬约束（配置只走 ConfigManager 保存、mixin 必须登记且客户端类进 client 组、第三方耦合走 compat 反射熔断）。
首次构建前跑 tools/prepare-lib.ps1 展开依赖。改完 compileJava + 对口自检，
升版本、写 CHANGELOG.md、productionJar、算哈希，最后按 docs/09 的状态报告模板输出。
```
