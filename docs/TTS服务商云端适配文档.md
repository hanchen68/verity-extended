# TTS 服务商云端适配文档

> 面向云端配置维护者：**怎么通过云端把语音（TTS）服务商推给玩家**，以及每家服务商要配什么。
>
> 配套文档：`Mod配置云端下发适配文档.md`（171 个配置项的总下发规则）。
> 本文只讲 `text_to_speech` 这一段与 **18 家 TTS 服务商**的适配细节。

---

## 1. 适用范围与两条下发通道

扩展的 TTS 有两条云端下发通道，**任选其一或叠加使用**：

| 通道 | 形态 | 适合场景 |
|---|---|---|
| **具名字段** `tts` | `{ "tts": { "apiProvider": "...", "apiUrl": "..." } }` | 常规调整：换服务商、换密钥、换音色 |
| **通用通道** `overrides` | `{ "overrides": { "text_to_speech.apiVoice": "..." } }` | 任意配置项，键用 TOML 点分路径 |

`tts` 具名字段只有 10 个（见 §3），**不覆盖** `tts_route.kind`、`modelPath` 等，那些必须走 `overrides`。

### 下发键名对照

| TOML 键 | 云端字段（`tts`） | 类型 |
|---|---|---|
| `text_to_speech.enableTts` | `enableTts` | 布尔 |
| `text_to_speech.mode` | `mode` | 字符串 `local` / `online` |
| `text_to_speech.apiProvider` | `apiProvider` | **枚举名，见 §2** |
| `text_to_speech.apiUrl` | `apiUrl` | 字符串 |
| `text_to_speech.apiKey` | `apiKey` | 字符串 |
| `text_to_speech.apiModel` | `apiModel` | 字符串 |
| `text_to_speech.apiVoice` | `apiVoice` | 字符串 |
| `text_to_speech.speed` | `speed` | 小数 |
| `text_to_speech.volume` | `volume` | 小数 |
| `text_to_speech.sampleRate` | `sampleRate` | 整数 |
| `text_to_speech.modelPath` | — | 只能用 `overrides` |
| `tts_route.kind` | — | 只能用 `overrides` |

> `apiProvider` 传的是 **Java 枚举常量名**（如 `FISH_TTS`），不是显示名（"Fish Audio"）——
> 传显示名会解析失败并静默回落到 `CUSTOM_OPENAI`。

---

## 2. 18 家服务商总表

`requestFormat` 列是模组内部用来选请求构造器的，**云端不需要配**，列出来只为排障。

| 枚举名（apiProvider） | 显示名 | 默认端点 | 鉴权 | 请求体形态 | 响应 |
|---|---|---|---|---|---|
| `CUSTOM_OPENAI` | 自定义 (OpenAI 兼容) | 空 | Bearer | openai | 原始音频 |
| `OPENAI_TTS` | OpenAI 官方 | api.openai.com/v1/audio/speech | Bearer | openai | 原始音频 |
| `KOKORO` | Kokoro 本地 | 127.0.0.1:8880/v1/audio/speech | Bearer | openai | 原始音频 |
| `ALIBABA_TTS` | 阿里云 | nls-gateway-cn-shanghai.aliyuncs.com | 阿里自有 | alibaba | 原始音频 |
| `TENCENT_TTS` | 腾讯云 | tts.tencentcloudapi.com | 腾讯自有 | tencent | 原始音频 |
| `BAIDU_TTS` | 百度智能云 | tsn.baidu.com/text2audio | 百度自有 | baidu | 原始音频 |
| `BYTEDANCE_TTS` | 火山引擎 | openspeech.bytedance.com | Bearer | bytedance | 原始音频 |
| `IFLYTEK_TTS` | 讯飞（WebSocket） | wss://tts-api.xfyun.cn | 讯飞自有 | — | **HTTP 不支持，别推** |
| `SILICONFLOW_TTS` | 硅基流动 | api.siliconflow.cn/v1/audio/speech | Bearer | siliconflow | 原始音频 |
| `MOONSHOT_TTS` | Moonshot 语音 | api.moonshot.cn/v1/audio/speech | Bearer | moonshot | 原始音频 |
| `QIANWAN_TTS` | 千帆大模型 | qianwan.bce.baidu.com/api/tts | Bearer | qianwan | 原始音频 |
| `FISH_TTS` | **Fish Audio** | api.fish.audio/v1/tts | Bearer | fish | 原始音频 |
| `MIMO_TTS` | 小米 MiMo | api.xiaomimimo.com/v1/chat/completions | api-key 头 | mimo | JSON(base64) |
| `GROQ_TTS` | **Groq** | api.groq.com/openai/v1/audio/speech | Bearer | openai | 原始音频 |
| `MINIMAX_TTS` | **MiniMax** | api.minimax.cn/v1/t2a_v2 | Bearer | minimax | 原始音频 |
| `CARTESIA_TTS` | **Cartesia** | api.cartesia.ai/tts/bytes | **X-API-Key** | cartesia | 原始音频 |
| `QWEN_TTS` | **通义千问 / CosyVoice** | **空，必须自填** | Bearer | qwen | **JSON(音频链接)** |
| `MOSS_TTS` | **Moss 魔音工坊** | api.mosi.cn/v1/audio/speech | Bearer | moss | **JSON(音频链接)** |

---

## 3. 新增六家的适配细节与下发示例

### 3.1 Fish Audio（`FISH_TTS`）

```
POST https://api.fish.audio/v1/tts
Authorization: Bearer <apiKey>
{"text":"<要说的话>","reference_id":"<音色>","format":"wav"}
→ 原始 WAV 字节
```

- **音色字段名是 `reference_id`**，不是 OpenAI 的 `voice`——这正是它必须独立分支的原因。
- 配置里「音色」一栏填的就是 **Reference ID**（Fish Audio 后台的音色模型 id）。
- 没填 Reference ID 时扩展会明确告警并放弃发请求，不会发个空音色上去。

```json
{ "tts": { "enableTts": true, "mode": "online", "apiProvider": "FISH_TTS",
           "apiUrl": "https://api.fish.audio/v1/tts", "apiKey": "<key>",
           "apiVoice": "<reference_id>" } }
```

### 3.2 Groq（`GROQ_TTS`）

请求体与 OpenAI **完全一致**，所以模组直接复用 openai 分支，没有额外字段。

```json
{ "tts": { "apiProvider": "GROQ_TTS", "apiUrl": "https://api.groq.com/openai/v1/audio/speech",
           "apiKey": "<key>", "apiModel": "playai-tts", "apiVoice": "<voice>" } }
```

### 3.3 MiniMax（`MINIMAX_TTS`）

```
POST https://api.minimax.cn/v1/t2a_v2
{"model":"<model>","text":"<text>",
 "voice_setting":{"voice_id":"<voice>","speed":1,"vol":1,"pitch":0},
 "audio_setting":{"sample_rate":32000,"format":"wav","channel":1}}
```

- 文本字段是 `text`（不是 `input`）；音色在 `voice_setting.voice_id` 里。
- 语速/音量/音调在模组里固定为 `1/1/0`，**不受 `text_to_speech.speed` 影响**——要改得改模组。

```json
{ "tts": { "apiProvider": "MINIMAX_TTS", "apiUrl": "https://api.minimax.cn/v1/t2a_v2",
           "apiKey": "<key>", "apiModel": "<model>", "apiVoice": "<voice_id>" } }
```

### 3.4 Cartesia（`CARTESIA_TTS`）

```
POST https://api.cartesia.ai/tts/bytes
X-API-Key: <apiKey>                 ← 不是 Authorization: Bearer
Cartesia-Version: 2026-08-14        ← 必须带，否则 400
{"model_id":"<model>","transcript":"<text>","voice":"<voice>",
 "output_format":{"container":"wav","encoding":"pcm_s16le","sample_rate":44100}}
```

- 🔴 **密钥走 `X-API-Key` 头**，这是它和别家最大的差别；推送时照常填 `apiKey`，
  模组会替用户放到正确的头上。
- 🔴 **必须带 `Cartesia-Version` 头**，模组已内置，云端不用管。
- 文本字段叫 `transcript`，模型字段叫 `model_id`。

```json
{ "tts": { "apiProvider": "CARTESIA_TTS", "apiUrl": "https://api.cartesia.ai/tts/bytes",
           "apiKey": "<key>", "apiModel": "<model_id>", "apiVoice": "<voice_id>" } }
```

### 3.5 通义千问 / CosyVoice（`QWEN_TTS`）

```
POST <apiUrl>                        ← 默认端点为空，必须由云端或玩家自填
{"model":"<model>","input":{"text":"<text>","voice":"<voice>","format":"wav"}}
→ JSON，音频是一个下载链接
```

- 🔴 **默认端点是空的**：北京/新加坡区域端点不同，必须下发 `apiUrl`。
  不下发时扩展会告警并放弃，不会拿到处乱试。
- 文本与音色都在嵌套的 `input` 对象里。
- 响应是 JSON、音频是链接 → 模组会自动下载（`url` 响应类型）。
- 模型可用 `cosyvoice-v2` / `cosyvoice-v3` / `qwen-audio-3.0-tts` 等。

```json
{ "tts": { "apiProvider": "QWEN_TTS",
           "apiUrl": "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
           "apiKey": "<key>", "apiModel": "cosyvoice-v2", "apiVoice": "<voice>" } }
```

### 3.6 Moss 魔音工坊（`MOSS_TTS`）

```
POST https://api.mosi.cn/v1/audio/speech
{"model":"<model>","input":"<text>","voice_id":"<voice>",
 "response_format":"wav","delivery_method":"url"}
→ JSON，音频是一个下载链接
```

- 文本字段叫 `input`，音色字段叫 `voice_id`。
- `delivery_method:"url"` 表示用链接取音频，模组会自动下载。

```json
{ "tts": { "apiProvider": "MOSS_TTS", "apiUrl": "https://api.mosi.cn/v1/audio/speech",
           "apiKey": "<key>", "apiModel": "<model>", "apiVoice": "<voice_id>" } }
```

---

## 4. 通用「音频链接」响应是什么

`QWEN_TTS` 和 `MOSS_TTS` 不直接回音频字节，而是回一段 JSON、音频在某个 `url` 字段里
（各家字段名不同：`output.audio.url`、`data.url`…）。模组的处理：

1. 收到响应后先看 `responseType`；
2. 为 `url` 时，**递归查找 JSON 里第一个 `http(s)` 字符串**（不写死字段名，深度限 6 层）；
3. 取到地址后下载，再走通用的音频校验与播放。

**云端不需要为这件事额外配置**，只要 `apiProvider` 选对了，模组自动处理。

---

## 5. 下发自检清单

每次推 TTS 配置前，逐条确认：

- [ ] `apiProvider` 是**枚举名**（`FISH_TTS` 这种），不是显示名；
- [ ] `mode` 是 `online`（`local` 会走离线模型，根本不联网）；
- [ ] `apiUrl` 非空——尤其 **`QWEN_TTS` 的默认端点是空的**，必须下发；
- [ ] `apiVoice` 已填——Fish Audio / MiniMax / Cartesia / 通义 / Moss 都**没有可用的默认音色**，
      空值会被明确拒绝而不是静默失败；
- [ ] Cartesia 的密钥填在 `apiKey`（模组会转成 `X-API-Key`），不要自己拼头；
- [ ] 没有推 `IFLYTEK_TTS`（讯飞是 WebSocket，HTTP 实现不支持，会被明确拒绝）；
- [ ] `overrides` 与具名字段**不要同时改同一个键**（具名字段优先，容易看不出谁生效）。

---

## 6. 常见问题排查

| 现象 | 大概率原因 | 处理 |
|---|---|---|
| 换了一家却没生效 | `apiProvider` 传了显示名，回落 `CUSTOM_OPENAI` | 传枚举名 |
| 完全没声音 | `mode` 还是 `local`，或 `tts_route.kind` 是 `native` | 改 `mode: online`；`overrides` 里把 `tts_route.kind` 设为 `endpoint` |
| 报「还没填音色」 | `apiVoice` 为空 | 补 `apiVoice` |
| 通义报「需要自行填写 API 地址」 | 忘了下发 `apiUrl` | 补 `apiUrl`（按区域） |
| Cartesia 一直 400/401 | 少了版本头或密钥放错头 | 模组已内置版本头；密钥只填 `apiKey` |
| 讯飞推不下去 | 选错成 `IFLYTEK_TTS` | 换一家 |
| 改完要重启才生效 | 云端配置只在**服务端启动**时拉取 | 重启存档/服务器 |

---

## 7. 变更记录

| 版本 | 变更 |
|---|---|
| 4.3 | TTS 服务商 **12 → 18 家**：新增 Fish Audio、Groq、MiniMax、Cartesia、通义千问/CosyVoice、Moss |
| 4.3 | 新增 `url` 响应类型（音频下载链接），供通义与 Moss 使用 |
| 4.3 | 新增 `tts_route` 段（三路朗读路线 auto/native/local/endpoint） |

---

## 8. 怎么验证下发生效

玩家侧最快的确认方式：

1. 重启游戏进世界；
2. 打开扩展设置 → 语音合成，看**服务商**一栏是否是云端推的那一家；
3. 按 V 说一句话（或等 AI 回话），听是否有声音。

开发侧可用日志确认（注意日志是 **GBK** 编码）：

```
在线TTS合成成功，原始响应大小: N 字节
已从响应地址下载音频，大小: N 字节     ← 只有 url 响应类型会出现这行
```

看到 `已从响应地址下载音频` 就说明通义/Moss 的链路通了。
