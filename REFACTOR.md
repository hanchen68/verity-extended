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
| P2a | `OllamaProxyServer` | 3441 → **3059** | `proxy/` 包。✅ 已完成：`ToolCallNormalizer` / `BillingGuard`（+ `BillingSettlement` 提为顶层）/ `StreamFlags` / `PromptInjections`。⏳ 未做：`ChatRequestAdapter`（剩余实例态注入）、`HttpRouter`（`handle()` 与 exchange/routes 深度耦合，建议不拆） | 中高（请求链路，必须有真实对话回归） |
| P2b | `config/NativeOllamaProxyConfigScreen` | 3060 → **2971** | 每个分类一个 `Category` 类，保留框架/绘制/滚动；行控件抽 `widget/` | 中（纯 UI，易回归截图） |
| P2c | `ConfigManager` | 1924 → **1911** | 保留为门面，抽 `voice/VoiceSettings`、`ai/ModelRouting`、`cloud/Credits`、`prompt/Persona` | 中（807 处调用依赖它，方法签名不能变） |
| P2d | `tts/OnlineTtsService` | 1948 → **1885** | `tts/engine/`：按协议拆分（OpenAI 兼容 / Mimo / 自定义），公共的重试与限流提到基类 | 中 |
| P2e | `action/AiBuildingManager` | 1861 → **1834** | 计划/执行/回滚三段拆开，蓝图 IO 独立 | 中高（会改世界） |
| P2f | `mmd/MmdPlaybackManager` | 1592 → **1577** | 加载 / 播放 / 相机 三层 | 中 |
| P2g | `client/VerityHorrorDirector` | 1254 → **1237** | 事件编排 / 表现（镜头、音效、文字） / 状态机 | 中 |

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

### P2a 收尾（2026-10-24 第三轮）

再把「注入链」和「建造意图」两块从处理器里搬出来，`OllamaProxyServer` **3243 → 3059**：

| 新类 | 内容 |
|---|---|
| `proxy/BuildingIntent` | 建造意图判定：100+ 关键词、动词/名词顺序、时间戳与 `<名字>` 前缀剥离。**有 `buildingIntentTest` 全量用例覆盖** |
| `proxy/ContextInjections` | 4 个需要运行时上下文的注入：玩家状态、记忆规则、建造协议、人设。取不到上下文一律静默跳过 |
| `proxy/PromptInjections`（补） | 新增 `appendSystemContextAtTail`——动态内容必须追加到**尾部**，否则上游前缀缓存全失效 |

**为什么建造意图值得单独成类**：它是全项目最容易改错的一块——判错就会**凭空开始建造**。
抽出来之后可以直接用测试跑全量用例，不用起游戏。

**分界线**：`PromptInjections` 只读配置拼固定文案；`ContextInjections` 要读世界/玩家/记忆，
所以「最后一条用户消息」由调用方当参数传进来，不在类里再取一遍。

### P2b 进度（2026-10-24）

抽出 `config/ConfigDisplay`（19 个静态纯函数）：配置值→中文、密钥脱敏、人设描述、
下拉选项数组、`clamp`、按像素裁剪的 `fitText`、说明文字分流 `isStatusValue`。
界面类 3060 → 2971 行，方法体全部改成一行委托，调用点零改动。

剩余可拆：15 个行控件（`Row`/`InfoRow`/`ToggleRow`/`TextRow`/`ChoiceRow`…，约 600 行）
是**非静态内部类**，依赖宿主屏幕的 `font` / `addRenderableWidget` / `requestRebuild`，
要拆得先定义一个宿主接口——收益中等、改动面大，建议等 P2c 之后单独一轮。

### P2c–P2g 进度（2026-10-24 第二轮）

六个上帝类全部动了刀，**每个都只抽「纯函数」层**，实例状态与主流程原地不动：

| 项 | 新类 | 抽出的内容 | 行数变化 |
|---|---|---|---|
| P2c | `config/ConfigText` | 空值收敛、层级归一、端点比对、Key 清洗、额度格式化 | 1924 → 1911 |
| P2d | `tts/AudioBytes` | PCM→WAV 封头、WAV/MP3 头识别、data 块定位、十六进制打印 | 1948 → 1885 |
| P2e | `action/BlueprintRules` | 方块分类、致命错误识别、坐标严格解析 | 1861 → 1834 |
| P2f | `mmd/MmdManifestParsing` | 清单字段解析 + **防目录穿越**的路径校验 | 1592 → 1577 |
| P2g | `client/HorrorRules` | 退出按钮识别、环境权重与概率封顶、实体类型判断 | 1254 → 1237 |

**统一做法**：只搬纯逻辑（不碰世界、不碰网络、不碰实例字段），原类保留同名委托，
调用点零改动。每抽一块立刻 `compileJava`，全部抽完跑一次全量自检（30 项）。

**没做的部分（有意为之）**：各类的「编排层」仍留在原处——
`AiBuildingManager` 的执行/回滚、`MmdPlaybackManager` 的播放状态机、
`VerityHorrorDirector` 的事件编排、`OnlineTtsService` 的播放与重试，
它们都强依赖实例状态与世界/客户端上下文，硬拆要传一长串上下文，
回归风险大于收益。真要拆，应按「状态机 → 独立对象」的方式重构，
而不是简单搬家。

### P3 进度（2026-10-24）

新增 `compat/ReflectiveTarget`，把「反射访问原版私有成员」的套路收成一处：

| 方法 | 用途 |
|---|---|
| `type(name, tag)` | `Class.forName`，失败 warn |
| `method` / `declaredMethod` | 取方法并 `setAccessible`，失败 debug |
| `field(owner, name, tag)` | 取字段并 `setAccessible`，失败 warn |
| `invoke` / `invokeStatic` | 调用并吞异常（debug） |
| `get` / `set` / `getFloat` / `getBoolean` | 字段读写，`set` 返回是否成功 |

**日志分级是有讲究的**：类缺失打 `warn`（版本不兼容是大事），方法与字段缺失打 `debug`
（版本间增删很常见，打 warn 会刷屏）。所有日志带 `tag`，一眼看出哪块功能在降级。

**只迁移了 `OriginalVerityStateAccess`**（302 → 279 行，它是唯一已经把反射集中成
`findClass/findMethod/findField` 的类）。其余 compat 类里的内联反射**故意不动**：
它们大多是 `try { Class.forName(...) } catch { return false; }` 这种**版本探测**，
换成 `type()` 会改变日志级别与降级行为——按「只搬代码、不顺手改逻辑」的规则，
这些应当在新写代码时逐步采用新 helper，而不是一次性机械替换。

🔴 `ReflectiveTarget` 只用于**已确定存在**的成员访问。判断「原版有没有某功能」
必须走 `VerityStructureDetector` 的字节扫描，`Class.forName` 会触发静态初始化、可能带崩启动。

拆的时候的硬规则：

1. 只搬代码 + 改可见性，**不顺手改逻辑**；每搬一块立刻 `compileJava`。
2. 对外方法签名保持兼容（老类留一个委托方法），避免 800 处调用点一起改。
3. 每完成一个类，跑一次 `selfcheck.ps1`，并在游戏里过一遍对应功能。
4. 一个类没拆完不要开下一个。

## P3 兼容层收敛 —— ✅ 已完成

`compat/ReflectiveTarget` 已落地（153 行），统一了 `type / method / declaredMethod /
field / invoke / get / set`，日志分级：类缺失 warn、方法与字段缺失 debug、全部带 tag。

**已迁移**：`OriginalVerityStateAccess`（302 → 279 行）——它是唯一已把反射集中成
`findClass/findMethod/findField` 的类，迁移零风险。

**未迁移（有意）**：其余 compat 类里的内联反射多是
`try { Class.forName(...) } catch { return false; }` 这种**版本探测**，
换成 `type()` 会改变日志级别与降级行为。按「只搬代码、不顺手改逻辑」的规则，
新代码用新 helper，老代码逐步迁移，不做一次性机械替换。

**仍然成立的约束**：判断「原版有没有某功能」必须走 `VerityStructureDetector`
的字节扫描——`Class.forName` 会触发静态初始化，可能直接带崩启动。

## 明确不做

- 不把 807 处 `ModConfig.X.get()` 改成访问器（云端覆盖依赖写 spec）；
- 不给 31 个 mixin 抽公共基类（会模糊 `require`/`remap` 语义）。
