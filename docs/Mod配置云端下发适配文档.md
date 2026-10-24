# Mod 配置云端下发适配文档

> 适用版本：Mod 4.3 及以上（`verity_extended`）  
> 对应云端接口：`POST {cloud.apiUrl}`（默认 `https://verity.xrstudio.cn/api/v1/getconfig`）  
> 配套文档：`docs/MMD资源中心云端适配文档.md`（资源下载，与本文件互不影响）、
> `docs/TTS服务商云端适配文档.md`（18 家语音服务商的端点/鉴权/请求体细节与下发示例）
>
> **实现状态：`overrides` 通道已落地，190/190 项均可云端下发**（Mod 4.4）。  
> 云端只要在响应里加一个 `data.overrides` 对象即可，**不需要 Mod 再发版**。  
> 自检：`gradlew cloudOverridesTest`。



---

## 1. 适配目标

**让云端可以下发 Mod 的全部配置开关，做到「默认所有功能都能在云端直接配置」。**

改动前只有一部分：云端只能改 LLM / STT / TTS 三块连接参数、内置线路选择和人设三项，共 **33 项**；  
其余 **138 项**（HUD、主动搭话、记忆、联网搜索、恐怖增强、AI 建造、多人服适配、MMD、皮肤、汉化、调试……）  
只能改本机 TOML，云端下发无效。

**现在（Mod 4.4）**：新增通用下发通道 `data.overrides`，键名 = TOML 点分路径，  
**190 项**全部可下发；以后新增配置项也不用再改云端协议。

本文档给出：

1. 现有拉取协议与生效规则（云端**必须保持不变**的部分）；
2. 具名字段覆盖的 33 项、`overrides` 覆盖的 138 项（完整清单）；
3. 通用下发通道 `overrides` 的协议与实现（第 7 节）；
4. 安全边界（哪些键禁止下发、为什么）；
5. 全部 190 个配置键的键名 / 类型 / 默认值 / 取值范围的对照表；
6. 云端侧实现清单与上线检查清单；
7. 已落地实现的细节与自检（第 12 节）。

---

## 2. 现有拉取协议（不要改动）

### 2.1 请求

```http
POST {cloud.apiUrl}
Authorization: Bearer <api-key>
Content-Type: application/json

{
  "mod_version": "4.3",
  "client_id": "<安装实例标识>",
  "billing_protocol_version": 2
}
```

- `client_id` 是 Mod 首次运行生成并持久化的随机标识，用于云端区分安装实例；同一个账号可以有多台设备。
- 地址必须是 HTTPS；只有本机回环地址允许 HTTP（`NetworkSecurity.requireSecureUri`）。

### 2.2 响应信封

```json
{
  "code": 0,
  "message": "success",
  "data": { "version": 42, "config_id": "cfg_xxx", "...": "..." },
  "billing": { "protocol_version": 2, "...": "..." }
}
```

成功判定（全部满足才算成功）：

| 条件                          | 说明                 |
| --------------------------- | ------------------ |
| HTTP 状态 `200`               | 其他状态一律视为失败         |
| `code == 0`                 | 缺失或非 0 视为失败        |
| `data != null`              | 缺失视为失败             |
| `data.version` 是 `>= 0` 的整数 | 缺失或为负视为失败（版本号是必需的） |

`billing` 允许放在顶层，也允许放在 `data.billing`；两者同时存在时以 `data.billing` 为准。

### 2.3 失败与版本语义

| 情况                            | Mod 行为                                    |
| ----------------------------- | ----------------------------------------- |
| HTTP `401` 或 `code == 401`    | 判定为「密钥失效」：**保留最近一次有效缓存**，不覆盖本地配置；客户端只提示一次 |
| HTTP `429` / `5xx` / 网络异常     | 退避重试（指数退避），保留当前运行时配置                      |
| `data.version` 比当前已应用的版本**小** | **整份配置被忽略**并记日志（防止乱序/回滚把配置打回去）            |
| `data.version` 与当前相同          | 允许应用（幂等重放）                                |

> ⚠️ **版本号必须单调递增**。云端每次改动配置都要 `version + 1`，否则玩家端不会生效。

### 2.4 本地快照与回退

启用 `cloud.fallbackToLocal`（默认 `true`）时，每次成功拉取都会把配置写到本地：

```text
config/verity_extended/cloud-snapshots/<api-key 的 SHA-256 前 16 字节>.json
```

- 快照结构：`{ "schema_version": 1, "version": 42, "config_id": "...", "config": { ... } }`
- **写入前会递归剥掉所有密钥字段**（字段名归一化后等于 `apikey` / `authorization` / `accesstoken` / `secret` 的一律删除）。  
  这是刻意的取舍：快照只保留线路、模型、定价等非敏感元数据；离线回退时内置线路拿不到 Token 会被跳过。
- 启动时若磁盘上有快照，先应用快照，再异步拉取最新配置。
- 快照按 API Key 隔离，换账号不会串配置。

### 2.5 拉取时机

| 配置项                     | 默认      | 说明                     |
| ----------------------- | ------- | ---------------------- |
| `cloud.pullOnStartup`   | `true`  | 启动时拉一次                 |
| `cloud.pullInterval`    | `0`     | 定期拉取间隔（秒）；`0` = 关闭定期拉取 |
| `cloud.timeout`         | `10`    | 单次请求超时（秒）              |
| `cloud.fallbackToLocal` | `true`  | 拉取失败时是否回退到本地快照         |
| `cloud.enable`          | `false` | **总开关；关闭时整份云端配置不生效**   |

客户端在进入世界时启动拉取，服务端在世界加载时启动拉取；两边各自独立拉取同一份配置。

---

## 3. 生效规则（当前实现，扩展设计会沿用）

### 3.1 单键规则

每个可下发的键都遵守同一句话：

> **云端给了非空值 → 用云端；云端没给（字段缺失或为 `null`，字符串为空/空白）→ 用本地 TOML。**

代码形态（`ConfigManager`）：

```java
public static boolean isEnableTts() {
    CloudConfig.Data cfg = cloud();
    if (cfg != null && cfg.tts != null && cfg.tts.enableTts != null) return cfg.tts.enableTts;
    return ModConfig.ENABLE_TTS.get();   // 本地 TOML 兜底
}
```

由此推出两条对云端很重要的性质：

1. **「不下发」等于「不干预」**。云端只想改 3 个开关时，其余 167 个键不要出现在响应里，玩家本地设置原样保留。
2. **想让某键回到本地值**：把该键从响应里删掉即可，不要下发空串（空串会被当成「没给」而忽略，但布尔/数字给 `null` 更明确）。

### 3.2 云端总开关

`cloud.enable = false` 时 `cloud()` 恒返回 `null` → **所有云端值失效**，完全走本地 TOML。  
云端无法远程打开这个开关（这正是下面第 9 节把它列入硬禁止的原因）。

### 3.3 作用域

配置分两类作用域，**同一份云端配置会被客户端与服务端各自拉取、各自生效**：

| 作用域 | 生效位置             | 典型键                                                                                                    |
| --- | ---------------- | ------------------------------------------------------------------------------------------------------ |
| 客户端 | 只影响玩家自己的画面/声音/输入 | `hud.*`、`camera_vision.*`、`mmd.*`、`skin.*`、`localization.*`、`tts_*`、`voice_language.*`、`builtin_pmx.*` |
| 服务端 | 影响世界规则与多人服行为     | `server.*`、`server_verity_adapter.*`、`horror_server.*`、`ai_building.*`（建造落地在服务端）                       |
| 两端  | 影响与模型/云的交互       | `llm.*`、`speech_recognition.*`、`text_to_speech.*`、`ai_extensions.*`、`proactive_awareness.*`            |

玩家在别人的服务器上时，服务端的键由**服务器管理员**的云端配置决定，客户端下发无效。云端做管理界面时建议按作用域分组提示。

---

## 4. 通过具名字段下发的键（33 项）

这些键有专门的云端字段，**至今仍是首选通道**（历史兼容，行为不变）。  
其余 157 项走第 7 节的 `overrides`，两条通道相加 = 全部 190 项。

| 云端字段                         | 对应本地键                                 | 说明                                                              |
| ---------------------------- | ------------------------------------- | --------------------------------------------------------------- |
| `llm.apiProvider`            | `llm.apiProvider`                     | 对话服务商标识                                                         |
| `llm.apiUrl`                 | `llm.apiUrl`                          | 对话接口地址                                                          |
| `llm.apiKey`                 | `llm.apiKey`                          | 对话密钥（快照中会被剥离）                                                   |
| `llm.model`                  | `llm.model`                           | 对话模型                                                            |
| `llm.requestTimeout`         | `llm.requestTimeout`                  | 请求超时（秒）                                                         |
| `stt.enableVoiceInput`       | `speech_recognition.enableVoiceInput` | 语音输入开关                                                          |
| `stt.mode`                   | `speech_recognition.mode`             | `local` / `online`                                              |
| `stt.apiProvider`            | `speech_recognition.apiProvider`      |                                                                 |
| `stt.apiUrl`                 | `speech_recognition.apiUrl`           |                                                                 |
| `stt.apiKey`                 | `speech_recognition.apiKey`           |                                                                 |
| `stt.model`                  | `speech_recognition.model`            |                                                                 |
| `stt.requestFormat`          | `speech_recognition.requestFormat`    | `multipart` / `json` / `url_encoded`                            |
| `stt.authType`               | `speech_recognition.authType`         | `bearer` / `api_key_header` / `api_key_param` / `custom_header` |
| `stt.responseField`          | `speech_recognition.responseField`    | 响应里取文本的字段名                                                      |
| `stt.voskModelPath`          | `speech_recognition.voskModelPath`    | 本地 Vosk 模型目录                                                    |
| `stt.sampleRate`             | `speech_recognition.sampleRate`       | 识别采样率                                                           |
| `tts.enableTts`              | `text_to_speech.enableTts`            | 朗读总开关                                                           |
| `tts.mode`                   | `text_to_speech.mode`                 | `local` / `online`                                              |
| `tts.sampleRate`             | `text_to_speech.sampleRate`           | 合成采样率                                                           |
| `tts.speed`                  | `text_to_speech.speed`                | 语速                                                              |
| `tts.volume`                 | `text_to_speech.volume`               | 音量                                                              |
| `tts.apiProvider`            | `text_to_speech.apiProvider`          |                                                                 |
| `tts.apiUrl`                 | `text_to_speech.apiUrl`               |                                                                 |
| `tts.apiKey`                 | `text_to_speech.apiKey`               |                                                                 |
| `tts.apiModel`               | `text_to_speech.apiModel`             |                                                                 |
| `tts.apiVoice`               | `text_to_speech.apiVoice`             | 音色                                                              |
| `builtin_api.enableChatApis` | `builtin_api.enableChatApis`          | 是否用云端内置对话线路                                                     |
| `builtin_api.enableSttApis`  | `builtin_api.enableSttApis`           | 是否用云端内置识别线路                                                     |
| `builtin_api.enableTtsApis`  | `builtin_api.enableTtsApis`           | 是否用云端内置合成线路                                                     |
| `builtin_api.mimoTtsVoice`   | `builtin_api.mimoTtsVoice`            | 内置 MiMo 音色                                                      |

另外还有一组**纯云端字段**（本地没有对应键，只在云端生效）：  
`pricing.*`（计价）、`billing.*`（计费协议与预留/结算地址）、`mimo.*`（MiMo 线路与音色列表地址）、  
`data.credits_remaining` / `data.credits_total`（额度）、`data.config_id`、`data.version`、  
`builtin_api` 下的线路选择（`chatTier` / `sttTier` / `ttsTier` / `chatRouteId` / `sttRouteId` / `ttsRouteId` /  
`routePolicy` / `chat` / `stt` / `tts` 线路数组）。

---

## 5. 没有具名字段的键（138 项）

以下配置段过去**完全没有**云端通道，只能改本机 TOML（完整键名见第 8 节）。  
现在它们全部可以通过 `overrides` 下发，键名就是本表左列的段名加点号，例如 `hud.dialogueHud`。

| 配置段                     | 项数 | 内容                          |
| ----------------------- | -: | --------------------------- |
| `server`                |  1 | 本地代理监听端口                    |
| `cloud`                 |  7 | 云端通道自身的开关与地址                |
| `llm`                   |  6 | 除连接参数与人设外的对话行为（双语字幕、流式、成长等） |
| `proactive_awareness`   | 18 | 主动搭话：全部事件开关、冷却、空闲间隔         |
| `hud`                   |  7 | 对话气泡、立绘、状态角标、导航箭头、沉浸聊天      |
| `camera_vision`         | 14 | 摄像头视觉：来源、设备、分辨率、视觉模型        |
| `speech_recognition`    |  1 | 一键安装 Vosk 模型                |
| `text_to_speech`        |  1 | 本地模型目录                      |
| `friendly_mode`         |  1 | 友好模式                        |
| `custom_persona`        |  1 | 自定义人设提示词                    |
| `tts_stream`            |  1 | 流式播报                        |
| `disable_original_tts`  |  1 | 是否关闭原版朗读                    |
| `tts_route`             |  1 | 三路朗读路线                      |
| `voice_language`        |  5 | 显示语言、朗读语言、翻译专用线路            |
| `ai_extensions`         | 15 | 记忆、上下文裁剪、背包上下文、对话日志、联网搜索    |
| `gameplay_safety`       |  1 | 是否允许恶魔转化                    |
| `server_verity_adapter` | 14 | 多人服适配：实体归属、上限、骑乘、站头、道具工具    |
| `horror_server`         |  4 | 服务端恐怖规则                     |
| `horror`                | 11 | 客户端恐怖增强                     |
| `ai_building`           | 14 | AI 建造：开关、限速、预览、持久化          |
| `mmd`                   |  4 | 相机高度、恶魔自动生成、重试间隔            |
| `builtin_pmx`           |  2 | 内置 PMX 模型                   |
| `skin`                  |  1 | 皮肤名                         |
| `localization`          |  1 | 原版界面汉化                      |
| `debug`                 |  3 | 调试开关                        |
| `plugin`                |  3 | 插件系统开关与上限                |

---

## 6. 已修复：3 个「声明了但从未生效」的字段

`CloudConfig.Llm` 里声明了这三个字段，但**整个项目没有任何一处读取它们**（已用全量搜索确认）：

| 字段                       | 控制                                 |
| ------------------------ | ---------------------------------- |
| `llm.enableCustomPrompt` | `llm.enableCustomPrompt`（是否启用人设覆盖） |
| `llm.selectedPreset`     | `llm.selectedPreset`（预设人设）         |
| `llm.customPromptFile`   | `llm.customPromptFile`（外部人设文件路径）   |

也就是说：**以前云端下发这三项是无效的，且不会报错**——「云端以为改了、其实没改」的静默失效。

**已修复**：三个字段现在都接上了取值链（`ConfigManager.isCustomPromptEnabled()` /  
`getSelectedPresetRaw()` / `getCustomPromptFileRaw()`），规则与其余云端字段一致（云端给了非空值就用云端，否则读本地）。

另外两个字段经复核属于**设计上就不需要**，保持不读，云端下发它们不会有任何效果，别配：

| 字段                                                                  | 为什么不用                                  |
| ------------------------------------------------------------------- | -------------------------------------- |
| `billing.reservationTtlSeconds`                                     | 预留超时由服务端强制执行，客户端不维护 TTL                |
| `mimo.voices[].canToggle` / `skins[].canToggle` / `mmd[].canToggle` | 客户端一律用 `isOwner` 推导「能否切换公开性」，不用云端这个提示位 |

---

## 7. 目标设计：通用下发通道 `overrides`

### 7.1 为什么不逐个加字段

现有做法是「一个云端字段对应一个本地键」，每个键都要在 `CloudConfig` 里加字段、  
在 `ConfigManager` 里加判断。要覆盖剩下 138 项就意味着 138 个字段 + 138 段样板代码，  
而且每加一个新功能都要云端和 Mod **同时发版**才能配置——这正是现在「配不全」的根因。

改用**一张覆盖表**：云端下发「本地键 → 值」的映射，Mod 端按同一套规则解释它。  
新增功能时只要沿用现有键名，云端立刻就能配，**不需要 Mod 再发版**。

### 7.2 JSON 形态

新增一个可选字段 `data.overrides`，两种写法都支持：

**推荐：扁平点号键**（键名与 TOML 完全一致）

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "version": 43,
    "config_id": "cfg_xxx",
    "overrides": {
      "hud.dialogueHud": false,
      "hud.dialogueSeconds": 20,
      "ai_extensions.memoryRetrievalTopN": 10,
      "ai_building.enabled": true,
      "ai_building.blocksPerTick": 4,
      "tts_route.kind": "endpoint",
      "voice_language.speak": "zh",
      "horror.intensity": 0.8
    }
  }
}
```

**也支持：嵌套对象**（云端按配置段组织更顺手，Mod 端会递归拍平成点号键）

```json
"overrides": {
  "hud": { "dialogueHud": false, "dialogueSeconds": 20 },
  "ai_building": { "enabled": true }
}
```

两种写法等价，可以混用；**同一个键同时出现时，扁平写法优先**（判定顺序：先拍平嵌套，再覆盖扁平）。

### 7.3 键名规范

- 键名 = `TOML 段名 + "." + TOML 键名`，与第 8 节的表**逐字一致**（大小写敏感）。
- 不要写 `ModConfig` 的 Java 常量名（如 `HUD_ENABLED`），那不是对外契约。
- 未知键：**忽略并记一条日志**，绝不让整份配置作废（前向兼容：云端可以先上新键，老 Mod 忽略即可）。

### 7.4 类型与校验

Mod 端以本地 TOML 的**声明类型**为准（全部 190 项只有四种类型：布尔 100、整数 32、小数 9、字符串 49），  
云端值的处理规则：

| 本地类型 | 接受的 JSON                                                                                         | 转换                       | 不合法时           |
| ---- | ------------------------------------------------------------------------------------------------ | ------------------------ | -------------- |
| 布尔   | `true` / `false`；兼容 `0` / `1`；兼容字符串 `"true"`/`"false"`/`"on"`/`"off"`/`"yes"`/`"no"`/`"1"`/`"0"` | 归一成布尔                    | **丢弃该键**并记警告日志 |
| 整数   | 数字；兼容数字字符串                                                                                       | 小数四舍五入到整数（`21.6` → `22`） | 同上             |
| 小数   | 数字；兼容数字字符串                                                                                       | 直接取 double               | 同上             |
| 字符串  | 字符串；数字/布尔会被转成文本                                                                                  | `String.valueOf`         | 对象/数组 → 同上     |

**范围校验直接借用 Forge 自己的声明**：把候选值塞进一份临时配置跑一遍 `ForgeConfigSpec.correct()`，  
被它判为不合法的键就丢弃。这样范围永远和 `ModConfig` 里 `defineInRange` 的声明一致，  
不需要在云端协议里再抄一份区间表（那种副本迟早会和声明走偏）。36 个数值键全部带声明范围。

> ⚠️ 与早期草案的差异：**超范围是「丢弃该键、保留本地值」，不是「钳制到区间内」**。  
> 钳制会让云端以为下发成功了，实际生效的是另一个数——静默失真比明确失败更难排查。

**枚举类字符串键**（如 `speech_recognition.mode`、`tts_route.kind`、`voice_language.speak`）本身是字符串，  
由各自的解析器做宽松匹配，认不出来时**回退到本地值**（解析器返回 null → 用本地），不会写入非法值。

**字符串长度不设上限**：`custom_persona.prompt` 这类键本来就可能是长文本，截断反而是静默损坏。  
规模由「512 键上限 + 响应体大小」兜底。


`overrides` 的规模上限：**512 个键**，超出的部分按顺序取前 512 个并记警告（防止异常响应把配置撑爆）。

### 7.5 与具名字段的关系（重要，容易踩）

两条通道**并存但互不感知**，各自的可见范围不同：

| 通道 | 谁能看到 |
|---|---|
| 具名字段（第 4 节那 33 项） | 只有走 `ConfigManager` 取值函数的代码。**直接读 `ModConfig.XXX.get()` 的代码看不到**（配置界面、`VoskModelInstaller`、`VoiceResourceManager`、`ClientEventHandler`…）。这是**改动前就存在**的性质，不是本次引入的 |
| `overrides` | 写穿到配置规格，**所有读法**都能看到（这正是它采用写穿的原因，见 7.7） |

所以：**同一个键不要同时用两条通道下发。**
同时下发时，取值函数返回具名值、直接读返回 `overrides` 值，两者不一致，排查起来很费劲。

- 第 4 节那 33 项：继续只用具名字段（或整体迁到 `overrides`，但不要并用）。
- 其余 157 项：只能用 `overrides`。
- 两者相加 = 190 项全覆盖。

### 7.6 生效时机、保存守卫与热重载

- `overrides` 与整份配置一起应用，遵守第 2.3 节的版本语义；`data.overrides` 缺失或为空对象 = 本次没有覆盖，
  会把上一轮的全部覆盖撤掉并还原成本地值。
- 应用后按现有机制触发热重载（`CloudRuntimeReload`）：客户端重建 HUD/语音链路，服务端重建适配层；
  不需要重启游戏或服务器。
- **不写回本地 TOML。** 写穿到配置规格之后，Forge 的 `ConfigValue.set()` 改的就是配置文件在内存里的那棵树，
  任何一次 `save()` 都会把云端值写进玩家的 TOML。因此**所有保存都必须走
  `ConfigManager.saveCommonConfig()` / `saveClientConfig()`**（`CloudOverrides` 的保存守卫）：
  保存前把被覆盖的键临时写回本地值，保存后再写回覆盖值。运行期行为不变，文件里留的始终是本地值。
  > 这条规则有源码级自检守着：`CloudOverridesTest` 会扫描 `src/main/java`，
  > 只要出现裸的 `COMMON_SPEC.save()` / `CLIENT_SPEC.save()` 就直接失败。
- **玩家自己改过的键，以玩家为准。** 保存时若发现某个键当前的值与云端写进去的不一致，
  说明是玩家（配置界面 / 管理员指令 / 其它模块）改的 → 采纳为新本地值、放弃该项覆盖，并记一条日志。
  放弃记录是**粘性**的：下一次拉取若云端给的还是同一个值，不会把玩家的设置抢回去；
  云端把这一项**改成别的值**时才重新接管（等于「云端改主意了就再管起来」）。
- 云端撤销某个键（从 `overrides` 里删掉）后，该键立刻回到本地值。

### 7.7 Mod 端改动点（已落地）

> ⚠️ **注意**：本节早期草案写的是「给每个配置项加一层 `cloudXxx(path, local)` 委托」。
> 那个方案**不可行**：项目里有 **509 处**直接读 `ModConfig.XXX.get()`（配置界面 276、恐怖事件 35、
> AI 建造 30、管理员指令 23……），只改 getter 的话，这些地方会**静默地拿到本地值**——
> 覆盖看起来生效了，实际一半功能没变。所以最终改成**写穿到配置规格**。

| 文件 | 改动 |
|---|---|
| `config/CloudOverrides.java` | **新增**。键索引、类型转换、范围校验、应用/还原、保存守卫 |
| `cloud/CloudConfig.java` | `Data` 增加 `public JsonObject overrides;` |
| `ConfigManager` | `applyCloudConfig()` 末尾调用 `CloudOverrides.apply(data.overrides)`；`clearCloudConfig()` 调 `CloudOverrides.clear()`；新增 `saveCommonConfig()` / `saveClientConfig()` |
| 6 个文件共 9 处 | 裸 `COMMON_SPEC.save()` / `CLIENT_SPEC.save()` 全部改走上面的包装 |
| `cloud/CloudSnapshotStore` | `isCredentialField` 改为**只看最后一段**——否则 `overrides` 里的点分键 `llm.apiKey` 整名比对不中，密钥会跟着快照落盘（已修，`CloudStorageTest` 断言保护） |

**键索引怎么拿全**：`ForgeConfigSpec.getValues()` 返回的树里，叶子就是 `ConfigValue` 对象本身
（`Builder.build()` 会把每个 ConfigValue 按路径塞进一棵新配置树），递归走一遍即可，**不需要反射读 `ModConfig` 的字段**。

**防漏自检（已实现）**：`CloudOverridesTest.everyConfigKeyIsReachable()` 用**两套互不相干的办法**各枚举一遍——
一套扫配置规格的树，一套反射读 `ModConfig` 的静态字段——两者必须完全一致，且总数必须是 190。
以后新增配置项若没被覆盖通道接上，或某个字段没进规格，测试会直接失败。

### 7.8 向后兼容

| 场景 | 行为 |
|---|---|
| 老云端不下发 `overrides` | 完全等同于现状，无任何变化 |
| 新云端下发 `overrides`，玩家用老 Mod | 老 Mod 忽略未知字段（Gson 默认行为），只应用具名字段 |
| 云端下发未知键 | 忽略 + 日志，其余键照常生效 |
| 云端下发越界/类型不符的值 | 丢弃该键、保留本地值 + 日志，其余键照常生效 |
| 云端下发 `cloud.*` | **一律丢弃** + 警告日志（见 9.1） |
| `cloud.enable = false` | `overrides` 与具名字段一起失效，且覆盖全部还原成本地值 |
| 同一批里混有非法键 | 非法键被丢弃，同批的合法键照常生效（不会整批作废） |

---


## 8. 全部配置键表（190 项）

> 「云端可下发」列的含义：**现在全部 190 项都是 ✅**。
> - **✅** = 有具名字段（第 4 节那 33 项），或可经 `overrides` 下发（其余 157 项）。
> - 键名列就是 `overrides` 用的键名，与 TOML 逐字一致。
> - 表中标 **—** 的项是历史遗留标记（写本表时还没有 `overrides` 通道），一律按 ✅ 理解。
>
> 例外有两组：第 9.1 节的 `cloud.*`（7 项）被硬禁止下发；
> `ai_extensions.webSearch*`（9 项）整组由本机管理，云端下发一律忽略（见 9.1.1）。


#### `[server]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `port` | 整数 | `4000` | `1~65535` | — |

#### `[llm]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `apiProvider` | 字符串 | `"CUSTOM_OPENAI"` | — | ✅ |
| `apiUrl` | 字符串 | `""` | — | ✅ |
| `apiKey` | 字符串 | `""` | — | ✅ |
| `model` | 字符串 | `""` | — | ✅ |
| `enableCustomPrompt` | 布尔 | `false` | `true`/`false` | — |
| `useOriginalVerityChatChain` | 布尔 | `true` | `true`/`false` | — |
| `bilingualSpeechSubtitles` | 布尔 | `false` | `true`/`false` | — |
| `bilingualSecondPass` | 布尔 | `true` | `true`/`false` | — |
| `overheadOnlyChat` | 布尔 | `false` | `true`/`false` | — |
| `enableVerityGrowth` | 布尔 | `false` | `true`/`false` | — |
| `chatStream` | 布尔 | `false` | `true`/`false` | — |
| `selectedPreset` | 字符串 | `"ORIGINAL_VERITY"` | — | — |
| `customPromptFile` | 字符串 | `"./config/verity_custom_prompt.txt"` | — | — |
| `requestTimeout` | 整数 | `30` | `5~300` | ✅ |

#### `[cloud]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enable` | 布尔 | `false` | `true`/`false` | — |
| `apiUrl` | 字符串 | `"https://verity.xrstudio.cn/api/v1/getconfig"` | — | — |
| `apiKey` | 字符串 | `""` | — | — |
| `pullOnStartup` | 布尔 | `true` | `true`/`false` | — |
| `pullInterval` | 整数 | `0` | `0~86400` | — |
| `timeout` | 整数 | `10` | `1~120` | — |
| `fallbackToLocal` | 布尔 | `true` | `true`/`false` | — |

#### `[builtin_api]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enableChatApis` | 布尔 | `false` | `true`/`false` | ✅ |
| `enableSttApis` | 布尔 | `false` | `true`/`false` | ✅ |
| `enableTtsApis` | 布尔 | `false` | `true`/`false` | ✅ |
| `mimoTtsVoice` | 字符串 | `"苏打"` | — | ✅ |

#### `[proactive_awareness]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enabled` | 布尔 | `false` | `true`/`false` | — |
| `pauseEvent` | 布尔 | `true` | `true`/`false` | — |
| `deathEvent` | 布尔 | `true` | `true`/`false` | — |
| `lowHealthEvent` | 布尔 | `true` | `true`/`false` | — |
| `sleepEvent` | 布尔 | `true` | `true`/`false` | — |
| `worldEvent` | 布尔 | `true` | `true`/`false` | — |
| `cooldownSeconds` | 整数 | `300` | `30~3600` | — |
| `desktopNotifications` | 布尔 | `false` | `true`/`false` | — |
| `dayNightEvent` | 布尔 | `false` | `true`/`false` | — |
| `weatherEvent` | 布尔 | `false` | `true`/`false` | — |
| `dimensionEvent` | 布尔 | `false` | `true`/`false` | — |
| `hungerEvent` | 布尔 | `false` | `true`/`false` | — |
| `levelEvent` | 布尔 | `false` | `true`/`false` | — |
| `healthTiers` | 布尔 | `true` | `true`/`false` | — |
| `idleChat` | 布尔 | `false` | `true`/`false` | — |
| `idleIntervalMinutes` | 整数 | `20` | `5~180` | — |
| `combatSilenceSeconds` | 整数 | `15` | `0~120` | — |
| `deferEvents` | 布尔 | `true` | `true`/`false` | — |

#### `[hud]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `dialogueHud` | 布尔 | `true` | `true`/`false` | — |
| `dialoguePortrait` | 布尔 | `true` | `true`/`false` | — |
| `statusBadge` | 布尔 | `false` | `true`/`false` | — |
| `navigationArrow` | 布尔 | `true` | `true`/`false` | — |
| `dialogueSeconds` | 整数 | `12` | `3~60` | — |
| `reminderEnabled` | 布尔 | `true` | `true`/`false` | — |
| `immersiveChat` | 布尔 | `false` | `true`/`false` | — |

#### `[camera_vision]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enabled` | 布尔 | `false` | `true`/`false` | — |
| `source` | 字符串 | `"camera"` | — | — |
| `backend` | 字符串 | `"auto"` | — | — |
| `ffmpegPath` | 字符串 | `""` | — | — |
| `deviceName` | 字符串 | `""` | — | — |
| `httpSnapshotUrl` | 字符串 | `""` | — | — |
| `width` | 整数 | `640` | `160~1920` | — |
| `height` | 整数 | `480` | `120~1080` | — |
| `jpegQuality` | 整数 | `70` | `10~100` | — |
| `captureTimeoutMs` | 整数 | `6000` | `1000~30000` | — |
| `sendOnProactiveEvent` | 布尔 | `false` | `true`/`false` | — |
| `skillEnabled` | 布尔 | `true` | `true`/`false` | — |
| `allowModelToSee` | 布尔 | `true` | `true`/`false` | — |
| `visionModel` | 字符串 | `""` | — | — |

#### `[speech_recognition]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enableVoiceInput` | 布尔 | `true` | `true`/`false` | ✅ |
| `mode` | 字符串 | `"local"` | — | ✅ |
| `apiProvider` | 字符串 | `"CUSTOM_OPENAI"` | — | ✅ |
| `apiUrl` | 字符串 | `""` | — | ✅ |
| `apiKey` | 字符串 | `""` | — | ✅ |
| `model` | 字符串 | `""` | — | ✅ |
| `requestFormat` | 字符串 | `"multipart"` | — | ✅ |
| `authType` | 字符串 | `"bearer"` | — | ✅ |
| `responseField` | 字符串 | `"text"` | — | ✅ |
| `voskModelPath` | 字符串 | `"./config/vosk-model-small-cn"` | — | ✅ |
| `installVoskModel` | 布尔 | `false` | `true`/`false` | — |
| `sampleRate` | 整数 | `16000` | `8000~48000` | ✅ |

#### `[text_to_speech]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enableTts` | 布尔 | `true` | `true`/`false` | ✅ |
| `mode` | 字符串 | `"online"` | — | ✅ |
| `sampleRate` | 整数 | `22050` | `8000~48000` | ✅ |
| `modelPath` | 字符串 | `"./config/tts-model"` | — | — |
| `speed` | 小数 | `1.0` | `0.25~4.0` | ✅ |
| `volume` | 小数 | `1.0` | `0.0~2.0` | ✅ |
| `apiProvider` | 字符串 | `"CUSTOM_OPENAI"` | — | ✅ |
| `apiUrl` | 字符串 | `""` | — | ✅ |
| `apiKey` | 字符串 | `""` | — | ✅ |
| `apiModel` | 字符串 | `""` | — | ✅ |
| `apiVoice` | 字符串 | `"alloy"` | — | ✅ |

#### `[friendly_mode]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enabled` | 布尔 | `false` | `true`/`false` | — |

#### `[custom_persona]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `prompt` | 字符串 | `""` | — | — |

#### `[tts_stream]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enabled` | 布尔 | `true` | `true`/`false` | — |

#### `[disable_original_tts]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enabled` | 布尔 | `true` | `true`/`false` | — |

#### `[tts_route]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `kind` | 字符串 | `"auto"` | — | — |

#### `[voice_language]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `display` | 字符串 | `"follow"` | — | — |
| `speak` | 字符串 | `"follow"` | — | — |
| `translateModel` | 字符串 | `""` | — | — |
| `translateUrl` | 字符串 | `""` | — | — |
| `translateApiKey` | 字符串 | `""` | — | — |

#### `[ai_extensions]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `memoryEnabled` | 布尔 | `true` | `true`/`false` | — |
| `memoryRetrievalEnabled` | 布尔 | `true` | `true`/`false` | — |
| `memoryRetrievalTopN` | 整数 | `6` | `1~20` | — |
| `memoryDecayDays` | 整数 | `30` | `1~365` | — |
| `memoryAutoWriteEnabled` | 布尔 | `false` | `true`/`false` | — |
| `memoryAutoWriteMinIntervalSeconds` | 整数 | `120` | `30~3600` | — |
| `memoryPerPlayer` | 布尔 | `true` | `true`/`false` | — |
| `conversationTrimEnabled` | 布尔 | `true` | `true`/`false` | — |
| `inventoryContextEnabled` | 布尔 | `true` | `true`/`false` | — |
| `inventoryMaxTypes` | 整数 | `12` | `3~40` | — |
| `conversationLogEnabled` | 布尔 | `true` | `true`/`false` | — |
| `conversationLogMaxEntries` | 整数 | `500` | `50~5000` | — |
| `webSearchEnabled` | 布尔 | `false` | `true`/`false` | — |
| `webSearchProvider` | 字符串 | `"TAVILY"` | — | — |
| `webSearchApiKey` | 字符串 | `""` | — | — |
| `webSearchAlways` | 布尔 | `false` | `true`/`false` | — |
| `webSearchCustomUrl` | 字符串 | `""` | HTTPS；http 仅限本机回环 | — |
| `webSearchCustomBody` | 字符串 | `{"query":"{query}","count":{count}}` | 支持 `{query}`/`{count}`/`{key}` | — |
| `webSearchCustomHeaders` | 字符串 | `Authorization: Bearer {key}` | 每行一个「名称: 值」 | — |
| `webSearchCustomResults` | 字符串 | `results` | 点号路径，留空=根即数组 | — |
| `webSearchCustomFields` | 字符串 | `title=title,content=summary\|snippet,url=url` | `输出名=字段\|回退` | — |

#### `[gameplay_safety]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `allowVerityDemonTransformation` | 布尔 | `true` | `true`/`false` | — |
| `allowVerityGift` | 布尔 | `true` | `true`/`false` | — |
| `allowVerityKick` | 布尔 | `true` | `true`/`false` | — |
| `crossSavePursuit` | 布尔 | `false` | `true`/`false` | — |
| `dailyKarmaGainCap` | 整数 | `3` | `0~20`（0=不限制） | — |

#### `[server_verity_adapter]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enabled` | 布尔 | `true` | `true`/`false` | — |
| `privateChat` | 布尔 | `true` | `true`/`false` | — |
| `requireCommand` | 布尔 | `true` | `true`/`false` | — |
| `allowPublicVerityChat` | 布尔 | `true` | `true`/`false` | — |
| `autoClaimUnowned` | 布尔 | `true` | `true`/`false` | — |
| `maxEntitiesPerPlayer` | 整数 | `1` | `1~64` | — |
| `demonMaxHealth` | 整数 | `400` | `20~4000` | — |
| `allowGlobalEntityPurge` | 布尔 | `false` | `true`/`false` | — |
| `ballRideEnabled` | 布尔 | `true` | `true`/`false` | — |
| `rideOwnerOnly` | 布尔 | `true` | `true`/`false` | — |
| `headPerchEnabled` | 布尔 | `true` | `true`/`false` | — |
| `aiEventCooldownTicks` | 整数 | `60` | `0~1200` | — |
| `itemToolsEnabled` | 布尔 | `true` | `true`/`false` | — |
| `itemQueryLimit` | 整数 | `40` | `5~200` | — |

#### `[horror_server]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enabled` | 布尔 | `true` | `true`/`false` | — |
| `ambientEvents` | 布尔 | `true` | `true`/`false` | — |
| `eventFrequency` | 小数 | `1.0D` | `0.0D~3.0D` | — |
| `cooldownSeconds` | 整数 | `45` | `10~3600` | — |

#### `[horror]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enabled` | 布尔 | `true` | `true`/`false` | — |
| `intensity` | 小数 | `1.35D` | `0.0D~2.0D` | — |
| `visuals` | 布尔 | `true` | `true`/`false` | — |
| `audio` | 布尔 | `true` | `true`/`false` | — |
| `cameraShake` | 布尔 | `true` | `true`/`false` | — |
| `jumpscares` | 布尔 | `true` | `true`/`false` | — |
| `hallucinations` | 布尔 | `true` | `true`/`false` | — |
| `ambientAnomalies` | 布尔 | `true` | `true`/`false` | — |
| `anomalyText` | 布尔 | `true` | `true`/`false` | — |
| `anomalyFrequency` | 小数 | `1.0D` | `0.0D~3.0D` | — |
| `localVerityPublicDefault` | 布尔 | `false` | `true`/`false` | — |

#### `[ai_building]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enabled` | 布尔 | `false` | `true`/`false` | — |
| `verityBuilderMode` | 布尔 | `false` | `true`/`false` | — |
| `overwriteBlocks` | 布尔 | `true` | `true`/`false` | — |
| `blocksPerTick` | 整数 | `2` | `1~32` | — |
| `maxSize` | 整数 | `64` | `3~64` | — |
| `maxBlocks` | 整数 | `32768` | `64~32768` | — |
| `ghostPreview` | 布尔 | `false` | `true`/`false` | — |
| `ghostPreviewTicks` | 整数 | `30` | `0~600` | — |
| `bossBarProgress` | 布尔 | `true` | `true`/`false` | — |
| `particles` | 布尔 | `true` | `true`/`false` | — |
| `sound` | 布尔 | `true` | `true`/`false` | — |
| `blueprintDir` | 字符串 | `"./config/verity_extended/blueprints"` | — | — |
| `persist` | 布尔 | `false` | `true`/`false` | — |
| `allowMultiplayer` | 布尔 | `false` | `true`/`false` | — |

#### `[mmd]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `cameraHeight` | 小数 | `2.4D` | `-16.0D~32.0D` | — |
| `autoSpawnDemon` | 布尔 | `true` | `true`/`false` | — |
| `friendlyMode` | 布尔 | `true` | `true`/`false` | — |
| `spawnRetryMs` | 整数 | `8000` | `2000~30000` | — |

#### `[builtin_pmx]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enabled` | 布尔 | `false` | `true`/`false` | — |
| `model` | 字符串 | `""` | — | — |

#### `[skin]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `skinName` | 字符串 | `"Verity"` | — | — |

#### `[localization]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enableVerityLocalization` | 布尔 | `true` | `true`/`false` | — |

#### `[debug]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `devMode` | 布尔 | `false` | `true`/`false` | — |
| `runtimeMetrics` | 布尔 | `false` | `true`/`false` | — |
| `runtimeMetricsIntervalSeconds` | 整数 | `60` | `5~3600` | — |

#### `[plugin]`

| 键 | 类型 | 默认 | 取值/范围 | 云端可下发 |
|---|---|---|---|:--:|
| `enabled` | 布尔 | `true` | `true`/`false` | — |
| `maxLoaded` | 整数 | `30` | `0~500` | — |
| `allowBlockEdit` | 布尔 | `false` | `true`/`false` | — |

---

## 9. 安全边界

「所有功能都能在云端配置」不等于「所有键都允许云端下发」。云端一旦被越权或误操作，
影响的是**每一个玩家客户端的运行时行为**，所以必须显式划出禁区。

### 9.1 硬禁止（云端永远不能下发）

| 键 | 理由 |
|---|---|
| `cloud.enable` | 总开关。若可远程开启，等于云端能自行启用一条玩家从未同意的通道 |
| `cloud.apiUrl` | **自举劫持**：改掉它之后，所有后续拉取（含密钥）都会流向任意端点。这是最危险的一项 |
| `cloud.apiKey` | 同上：等于让云端替换掉自己的鉴权凭据 |
| `cloud.pullOnStartup` / `cloud.pullInterval` / `cloud.timeout` / `cloud.fallbackToLocal` | 影响通道自身的可用性与隐私（轮询频率） |

> **已在 Mod 端强制实现**：`overrides` 里以 `cloud.` 开头的键一律直接丢弃并记警告日志
> （`CloudOverridesTest.forbiddenSectionIsDropped()` 断言保护）。这一条不依赖云端自觉。

### 9.1.1 联网搜索：整组由本机管理（无条件）

`ai_extensions.webSearch*` 共 **9 项**（开关 / 服务商 / 密钥 / 每轮强制 / 自定义接口 5 项）
**不参与云端下发**：

- 服务商与密钥只存在本地，云端下发不匹配的服务商（本地 DeepSeek 密钥 + 云端改成 TAVILY）
  会让搜索直接失效；
- 云端下发 `false` 会造成「TOML 里是开着的、运行时是关的」，排查成本极高；
- 反向同样不允许：云端不能替玩家打开一个他没有配置密钥的搜索。

Mod 端在 `CloudOverrides.apply()` 中直接跳过整组并记一条日志，
`CloudOverridesTest.webSearchSettingsAreLocalOnly()` 断言保护。

> 因此「190 项都在覆盖通道索引里」不等于「190 项都能被云端改」：
> `cloud.*` 7 项 + `webSearch*` 9 项由本机/安全规则保留。

### 9.2 建议禁止（本机文件系统路径）

云端下发路径 = 让远端决定本机读写位置。**Mod 端不拦这些键**（要「全都能配」就不能挑键），
但云端管理界面应当默认隐藏或折叠它们；若确实需要下发，建议限制在受信任的账号级配置里：

| 键 |
|---|
| `camera_vision.ffmpegPath` |
| `speech_recognition.voskModelPath`（当前已开放，建议收敛） |
| `text_to_speech.modelPath` |
| `ai_building.blueprintDir` |
| `llm.customPromptFile` |
| `builtin_pmx.model` |
| `server.port` |

### 9.3 需二次确认（改变游戏内容或具破坏性）

云端管理界面应对这些键给出显式警示与确认步骤，并记录审计日志：

| 键 | 风险 |
|---|---|
| `gameplay_safety.allowVerityDemonTransformation` | 决定原版整条「恶魔化 / 永夜」剧情是否发生 |
| `friendly_mode.enabled` | 会取消恶魔转化分支，属于剧情级改动 |
| `server_verity_adapter.allowGlobalEntityPurge` | **破坏性**：允许清空全服 Verity 实体（含已有归属的） |
| `server_verity_adapter.maxEntitiesPerPlayer` / `demonMaxHealth` | 直接影响服务器负载 |
| `horror.*` / `horror_server.*` | 惊吓内容，涉及玩家体验与光敏性 |
| `debug.devMode` / `debug.runtimeMetrics` | 额外日志，可能包含更多运行细节 |
| 所有 `*apiKey` 字段 | 下发即**覆盖玩家自己配置的密钥**。内置线路需要该能力，但必须限制在账号级配置里，且日志中不得回显 |

### 9.4 推荐首批开放（低风险、见效快）

`hud.*`（7 项）、`proactive_awareness.*`（18 项）、`ai_extensions.*` 中除密钥外的 13 项、
`tts_route.kind`、`voice_language.display` / `speak`、
`llm.bilingualSpeechSubtitles` / `bilingualSecondPass` / `overheadOnlyChat` / `chatStream` /
`useOriginalVerityChatChain` / `enableVerityGrowth`、`localization.*`、`mmd.cameraHeight`。

这一批覆盖了玩家最常调的「表现层」开关，且**不涉及密钥、路径与剧情**，适合作为第一批上线内容。

---

## 10. 云端侧实现清单

### 10.1 数据模型

```text
config_accounts
  id / owner_user_id
  config_id            -- 下发到 data.config_id
  version              -- 单调递增，任何写操作 +1
  payload_json         -- typed 字段（llm/stt/tts/builtin_api/pricing/billing/mimo）
  overrides_json       -- 通用覆盖表（第 7 节）；Mod 4.3 起已生效
  updated_at / updated_by

config_audit_log
  id / owner_user_id / config_id / version
  changed_keys_json    -- ["hud.dialogueHud", ...]
  before_json / after_json
  operator / created_at
```

建议支持两层：**平台默认**（对所有账号生效）+ **账号级覆盖**（优先）。账号级未设置的键回落到平台默认，
平台默认也没有的键则**不出现在响应里**——让玩家本地设置生效。

### 10.2 接口

现有接口即可，**不需要新端点**，只在 `data` 里增加 `overrides`：

```http
POST /api/v1/getconfig
Authorization: Bearer <api-key>
Content-Type: application/json

{ "mod_version": "4.3", "client_id": "...", "billing_protocol_version": 2 }
```

响应（`overrides` 为可选字段）：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "version": 43,
    "config_id": "cfg_xxx",
    "credits_remaining": 1200.5,
    "credits_total": 2000,
    "llm": { "apiUrl": "...", "apiKey": "...", "model": "..." },
    "overrides": {
      "hud.dialogueHud": false,
      "ai_extensions.memoryRetrievalTopN": 10
    }
  }
}
```

### 10.3 服务端校验（重要）

Mod 端对非法输入是**宽容**的（丢弃并记日志），云端必须**严格**：

1. 键名必须存在于第 8 节的表里，否则拒绝保存（避免「云端以为改了、Mod 静默忽略」——第 6 节就是这么发生的）。
2. 类型与范围按表校验；越界直接报错，不要指望 Mod 端帮你改（**Mod 端是丢弃该键、保留本地值**，不是钳制）。
3. 第 9.1 节的 `cloud.*` 直接拒绝保存（Mod 端也会兜底丢弃，但云端不该让它出现）。
4. 第 9.2 / 9.3 节的键要求显式确认并写审计日志。落地口径：
   - **审计日志必须实现，且覆盖所有配置保存**（不只敏感键）：
     `config_audit_log` 记录 `changed_keys_json`、before/after、operator、created_at；
     `*apiKey` 等敏感值在日志里只记「已修改 / 未变化 / 已删除」，不存明文。
   - **二次确认**：当本次保存会让 9.2/9.3 键的**生效值发生变化**
     （新增、修改、删除都算）时，请求必须带：
     `confirmSensitive: true` 和非空 `confirmReason`（≤200 字）；
     否则返回 `400`，并列出需要确认的键。
     管理界面可以先不做，接口层先挡住，后续界面再接这个标记。
5. `overrides` 键数上限 512。
6. **同一个键不要同时出现在具名字段和 `overrides` 里**（见 7.5），保存时做互斥校验。落地口径：
   - 只校验「具名字段在 Mod 端有对应本地键」的那部分（第 4 节的 33 项映射）；
     `pricing.*`、`billing.*`、`mimo.*`、`builtin_api` 线路数组这类**云端专属字段**不参与。
   - 具名字段为 `null` / 缺省时视为未下发，不算冲突。
   - 同一键两条通道都出现且**值不同**：返回 `400`，并列出冲突键，让调用方改；
   - 两条通道都出现且**值完全相同**：允许保存（幂等，不会产生行为分歧）。
7. 保存成功后 `version + 1`；支持「草稿 / 发布」两态，发布时才对外可见。

### 10.4 不要做的事

- ❌ 不要因为「想统一体验」就把 190 项全量下发。这会让玩家本地设置全部失效，
  而且一旦云端配置有问题，所有人都同时出问题。**只下发真正需要强制的键。**
- ❌ 不要在响应里下发空串来表示「清空」。要撤销某个键，直接从 `overrides` 里删掉。
- ❌ 不要在 `version` 上做回滚。Mod 端会忽略版本号变小的整份配置（第 2.3 节）。
- ❌ 不要把密钥写进快照可回退的部分去指望离线可用——Mod 端会主动剥离密钥字段。

---

## 11. 上线检查清单

**协议层**

- [ ] `POST /api/v1/getconfig` 保持 `code` / `message` / `data` 信封，HTTP 成功状态为 `200`。
- [ ] `data.version` 为 `>= 0` 的整数且**每次改动递增**。
- [ ] 地址为 HTTPS（本机回环可用 HTTP）。
- [ ] `401` 用于密钥失效，且不覆盖玩家已有缓存。

**功能层**

- [ ] 现有 30 项 typed 字段行为与升级前完全一致（回归测试）。
- [ ] `data.overrides` 支持扁平点号键与嵌套对象两种写法。
- [ ] 只下发想强制的键；其余键不出现在响应中。
- [ ] 撤销某个键（删除 + `version + 1`）后，玩家端该键回到本地值。
- [ ] 未知键、越界值、类型不符的值都不会让整份配置作废。
- [ ] **同一个键不要同时用 typed 字段和 `overrides` 下发**（见 7.5）。

**安全层**

- [ ] `cloud.*` 全部键在云端**无法**被配置，或在 Mod 端被丢弃。
- [ ] 路径类键（第 9.2 节）已禁止或已限制在 `config/verity_extended/` 内。
- [ ] 第 9.2 / 9.3 节的键在生效值变化时要求 `confirmSensitive` + `confirmReason`。
- [ ] 所有配置保存都写入 `config_audit_log`；敏感值不落明文。
- [ ] 密钥字段不出现在任何日志、快照或客户端提示中（含 `overrides` 里的点分键）。

**验证用例**

- [ ] 老云端（不下发 `overrides`）+ 新 Mod：行为无变化。
- [ ] 新云端（下发 `overrides`）+ 老 Mod：老 Mod 忽略未知字段，仅 typed 字段生效。
- [ ] `cloud.enable = false`：`overrides` 与 typed 字段全部失效，且已生效的覆盖还原成本地值。
- [ ] 断网启动 + 有磁盘快照：先应用快照，再恢复拉取。
- [ ] 同账号两台设备：配置一致；换账号后配置不串。
- [ ] 云端下发覆盖后，玩家在配置界面点保存：**TOML 文件里仍是本地值**（不会被云端值污染）。

---

## 12. 已落地实现说明（Mod 4.3）

### 12.1 核心机制：写穿 + 保存守卫

`overrides` **不做 getter 委托**，而是把值直接写进 `ForgeConfigSpec`
（`CloudOverrides.apply()` → `ConfigValue.set()`）。原因是项目里有 **509 处**直接读
`ModConfig.XXX.get()`，只包 getter 会让这些地方静默拿到本地值。

写穿的副作用是「`save()` 会把云端值写进玩家 TOML」，因此加了保存守卫：
所有保存改走 `ConfigManager.saveCommonConfig()` / `saveClientConfig()`，
保存前把被覆盖的键临时还原成本地值、保存后再写回覆盖值。

### 12.2 一次下发的处理顺序

```text
data.overrides
  → 递归展平（嵌套 + 点分键，点分优先）
  → 512 键上限
  → cloud.* 硬丢弃 / ai_extensions.webSearch* 本地保留 / 未知键忽略（各记一条汇总日志）
  → 规格未加载的段跳过（服务端上 CLIENT_SPEC 永远不加载）
  → 按声明类型转换（不合法的丢弃）
  → 借 ForgeConfigSpec.correct() 做范围校验（不合法的丢弃）
  → 写进配置规格
  → 本轮没再出现的旧覆盖还原成本地值
```

### 12.3 自检

`gradlew cloudOverridesTest`（`src/test/java/.../config/CloudOverridesTest.java`）：

| 用例 | 断言内容 |
|---|---|
| `everyConfigKeyIsReachable` | 规格树枚举 == `ModConfig` 字段枚举，总数 190，`cloud.*` 恰好 7 项 |
| `coercionFollowsDeclaredType` | 布尔/整数/小数/字符串四种类型的转换与拒绝 |
| `flattenPrefersFlatForm` | 嵌套、点分、两者并存时点分优先 |
| `outOfRangeValuesAreRejected` | 越界丢弃、边界值接受 |
| `forbiddenSectionIsDropped` | `cloud.*` 被丢弃，且不拖累同批合法键 |
| `unknownAndEmptyPayloadsAreTolerated` | 未知键忽略；`null` / `{}` 视为「本次无覆盖」并清空 |
| `overridesApplyAndClearRestoresLocal` | 应用 / 重复下发 / 撤销单项 / clear 还原 |
| `saveGuardKeepsPlayerTomlClean` | **保存后 TOML 里是本地值，运行期是覆盖值** |
| `webSearchSettingsAreLocalOnly` | 云端无论下发开/关与服务商，`ai_extensions.webSearch*` 都保持本地值，且不计入生效覆盖 |
| `localEditBeatsCloudOverride` | 玩家改动被采纳并落盘；同一值不再抢回；云端改值后重新接管 |
| `saveSitesGoThroughGuard` | 源码里不存在裸的 `COMMON_SPEC.save()` / `CLIENT_SPEC.save()` |

`CloudStorageTest` 另外断言：`overrides` 里的点分密钥键（`llm.apiKey`）与嵌套密钥键都不会落进磁盘快照。

### 12.4 云端接入最小示例

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "version": 44,
    "config_id": "cfg_xxx",
    "overrides": {
      "hud.dialogueHud": false,
      "hud.dialogueSeconds": 20,
      "proactive_awareness.enabled": true,
      "proactive_awareness.cooldownSeconds": 180,
      "ai_extensions.enableWebSearch": false,
      "tts_route.kind": "endpoint",
      "voice_language.display": "zh",
      "voice_language.speak": "zh",
      "horror_server.eventFrequency": 0.6,
      "server_verity_adapter.maxEntitiesPerPlayer": 2
    }
  }
}
```

> 键名全部取自第 8 节的表；`version` 必须比上一次大。

---

## 附：相关源码位置（Mod 侧）

| 内容 | 位置 |
|---|---|
| **通用下发通道（键索引 / 转换 / 校验 / 保存守卫）** | `config/CloudOverrides.java` |
| 云端响应结构（含 `overrides`） | `cloud/CloudConfig.java` |
| 拉取协议与失败处理 | `cloud/CloudConfigFetcher.java` |
| 拉取调度 / 快照 / 热重载 | `cloud/CloudConfigManager.java`、`cloud/CloudSnapshotStore.java` |
| 凭据（API Key / client_id） | `cloud/CloudCredentialStore.java` |
| 生效规则（具名字段 → 本地回落） | `ConfigManager`（`cloud()` 及 `cfg.*` 判断） |
| 保存唯一入口（含守卫） | `ConfigManager.saveCommonConfig()` / `saveClientConfig()` |
| 全部配置键定义 | `config/ModConfig.java`（`COMMON_SPEC` / `CLIENT_SPEC`） |
| 自检 | `src/test/java/.../config/CloudOverridesTest.java` |
