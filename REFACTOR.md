# 重构进度与路线

> 目标：把「老代码」的可维护性拉回来，**不改变游戏行为**，每一步都可编译、可回滚。
> 安全网：`tools/selfcheck.ps1`（编译 + 4 个必跑自检，`-Full` 追加 5 项）。

## 现状体检（2026-10-24 实测）

| 指标 | 数值 |
|---|---|
| 源文件 / 行数 | 281 / 57,012 |
| 超 1000 行的类 | 8 |
| `ModConfig.X.get()` 直接引用 | 807（**有意设计，不改**） |
| 空 `catch` | 109（已全部处理，见 P1） |
| 静态可变字段 | 1330（多数是缓存，逐类评估，不强推） |

## P0 安全网 —— 已完成

- `tools/selfcheck.ps1`：`compileJava` + `cloudOverridesTest` + `mixinPackageHygieneTest`
  + `verityStructureDetectorTest` + `pluginTest`；`-Full` 追加 `cloudProtocolTest`、
  `resourceCollisionTest`、`verityLocalizationTest`、`ttsRoutePlannerTest`、`verityHorrorDirectorTest`。
- 安全网立刻抓到两个历史遗留问题（见 P1），证明它有必要。

## P1 清理 —— 已完成

1. **删除死代码** `config/OllamaProxyConfigScreen.java`（671 行，0 引用）。
2. **109 处空 catch 收口**：统一为 `util/Silent.ignore("类名", e)`。
   - 行为不变：仍然是吞掉、继续；
   - 仅 DEBUG 级别打印位置标签，关闭 DEBUG 时连字符串拼接都不做；
   - 覆盖 44 个文件。
3. **修 `CloudOverrides` 在无 Forge 环境的 NPE**：`FMLPaths.CONFIGDIR.get()` 可能为 null，
   兜底到工作目录 `config/`（自检、工具进程不再崩）。
4. **修正配置项总数哨兵** 181 → 190，并写明这个数字为什么存在。

## P2 拆分上帝类 —— 待做（按顺序，一次一个）

| 顺序 | 目标 | 行数 | 建议拆法 | 风险 |
|---|---|---|---|---|
| P2a | `OllamaProxyServer` | 3441 → **3243** | `proxy/` 包。✅ 已完成：`ToolCallNormalizer` / `BillingGuard`（+ `BillingSettlement` 提为顶层）/ `StreamFlags` / `PromptInjections`。⏳ 未做：`ChatRequestAdapter`（剩余实例态注入）、`HttpRouter`（`handle()` 与 exchange/routes 深度耦合，建议不拆） | 中高（请求链路，必须有真实对话回归） |
| P2b | `config/NativeOllamaProxyConfigScreen` | 3060 | 每个分类一个 `Category` 类，保留框架/绘制/滚动；行控件抽 `widget/` | 中（纯 UI，易回归截图） |
| P2c | `ConfigManager` | 1924 | 保留为门面，抽 `voice/VoiceSettings`、`ai/ModelRouting`、`cloud/Credits`、`prompt/Persona` | 中（807 处调用依赖它，方法签名不能变） |
| P2d | `tts/OnlineTtsService` | 1949 | `tts/engine/`：按协议拆分（OpenAI 兼容 / Mimo / 自定义），公共的重试与限流提到基类 | 中 |
| P2e | `action/AiBuildingManager` | 1861 | 计划/执行/回滚三段拆开，蓝图 IO 独立 | 中高（会改世界） |
| P2f | `mmd/MmdPlaybackManager` | 1590 | 加载 / 播放 / 相机 三层 | 中 |
| P2g | `client/VerityHorrorDirector` | 1254 | 事件编排 / 表现（镜头、音效、文字） / 状态机 | 中 |

### P2a 进度（2026-10-24）

已抽出四块，共 -198 行；**原类一律保留同名委托，调用点零改动**：

| 新类 | 内容 | 为什么能安全抽出 |
|---|---|---|
| `proxy/ToolCallNormalizer` | `extractContentText` / `sanitizeAssistantHistory` / `hasValidToolCalls` 等 7 个 | 纯 JSON 读写，无实例状态 |
| `proxy/BillingGuard` | settle / release / 用量上报 / 缓存命中统计 | 只依赖 `BillingService`、`UsageMeter` |
| `proxy/BillingSettlement` | 原内部 record 提为顶层类型 | 让 `BillingGuard` 能用，`OllamaProxyServer` 加一行 import 即可，14 处引用不变 |
| `proxy/StreamFlags` | `stream` 标志的读/强制关/强制开 | 纯 JSON 字段改写 |
| `proxy/PromptInjections` | 双语、视觉技能、给物品、联网指引 + `appendSystemContext`/`appendSkillRule` | 只读配置、拼文案、改 bodyJson |

**故意没拆**：`handle()`（HttpRouter）与 `handleStreamingChat` 深度绑定
`HttpExchange`、线路列表、结算列表与若干 ThreadLocal，硬拆要传一长串上下文，
收益小于回归风险。剩余可拆的是 `injectPlayerStateContext` / `injectMemoryRules` /
`injectBuildingContext` / `injectCustomSystemPrompt` 四项——它们读实例上下文
（玩家状态快照、记忆库、建造意图），要连依赖一起搬，建议单独一轮做。

拆的时候的硬规则：

1. 只搬代码 + 改可见性，**不顺手改逻辑**；每搬一块立刻 `compileJava`。
2. 对外方法签名保持兼容（老类留一个委托方法），避免 800 处调用点一起改。
3. 每完成一个类，跑一次 `selfcheck.ps1`，并在游戏里过一遍对应功能。
4. 一个类没拆完不要开下一个。

## P3 兼容层收敛 —— 待做（最后）

`compat/` 34 个类里，`StellarRemoteCompat`、`OriginalVeritySttInjector`、`OriginalVerityVoiceBridge`
等重复同一套「反射 + 熔断」样板，可抽 `compat/reflect/ReflectiveTarget`：

```java
ReflectiveTarget target = ReflectiveTarget.of("varmite.verity...", "可选模组名");
Method m = target.method("sendReplyPart", String.class);
if (m != null) { ... }
```

**必须在 P2 稳定后再做**：这是最容易碰坏适配的一层，且没有运行时回归就发现不了。

## 明确不做

- 不把 807 处 `ModConfig.X.get()` 改成访问器（云端覆盖依赖写 spec）；
- 不给 31 个 mixin 抽公共基类（会模糊 `require`/`remap` 语义）。
