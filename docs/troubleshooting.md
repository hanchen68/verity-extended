# 常见报错与解决方案

按日志关键字定位问题。每条给出：**日志关键字 → 根因 → 解决方案 → 验证方式**。

## 排查顺序（推荐）

1. **确认是否收到请求** —— 代理日志里有没有出现「收到Verity请求」
2. **确认上游是否可达** —— 401 / 403 / 504 / 超时
3. **确认 TTS** —— 404 / 格式 / 解码 / 音频播放
4. **确认 STT** —— 录音设备 / 采样率 / 识别
5. **确认云端** —— `cloud.enable` 是否被写成 false（重启后连不上优先查这条）
6. **确认 Dedicated Server** —— invalid dist

---

## 1. 不会回复 / 没反应（代理未收到请求）

`链路` `代理` `Chat`

代理日志里没有「收到Verity请求」，通常说明代理没启动，或 Verity 指向了错误的地址。

**日志关键字**

```text
========== 收到Verity请求
代理服务器已启动
目标API地址
```

**根因**

联机进入服务器时本地代理未启动；或 Verity 侧填了 `localhost` 解析到 IPv6 导致连不上；或端口不一致。

**解决方案**

优先把 Verity 的代理地址填成 `http://127.0.0.1:4000`；确认端口一致；启动后在日志里确认出现「收到Verity请求」。

**验证方式**

发送一句对话，代理日志出现「收到Verity请求」，并继续输出「目标API地址」「API响应状态码」。

---

## 2. API connection failed（504 / Connect timed out）

`504` `超时` `上游`

代理已收到请求，但连接上游超时。属于网络或域名可达性问题。

**日志关键字**

```text
API请求超时: Connect timed out
SocketTimeoutException
Status: 504
```

**根因**

机器无法访问上游域名（网络 / DNS / 代理 / 防火墙 / 目标服务异常），或 `apiUrl`、`endpoint` 填错。

**解决方案**

改用可达的上游服务，或检查 `apiUrl` 是否正确、是否需要经代理访问；必要时适当提高请求超时
（`requestTimeout`）。

**验证方式**

代理日志出现「API响应状态码=200」，并有「最终回复内容…」。

---

## 3. HTTP 401：Invalid API Key（invalid_api_key）

`401` `鉴权` `Key`

Key 无效，或混用了不同服务商的 Key。

**日志关键字**

```text
HTTP Error 401
Invalid API Key
invalid_api_key
```

**根因**

Key 错误 / 过期 / 被封；或 Key 与该服务商不匹配；或粘贴时带了空格、引号、不可见字符。

**解决方案**

重新生成 Key；粘贴前后去掉空格；不要带引号；确认服务商与 Key 的对应关系。

**验证方式**

401 消失，出现 200 响应或正常回复。

---

## 4. TTS 404：Invalid URL (POST /v1)

`TTS` `404` `URL`

TTS 地址只填到了基础路径（如 `.../v1`），缺少具体端点。

**日志关键字**

```text
在线TTS请求失败，状态码: 404
Invalid URL (POST /v1)
```

**根因**

接口地址缺少 endpoint 路径，或所选服务商（provider）与 API 端点不匹配（例如把 OpenAI 格式的地址填给了使用
对话接口的服务商）。

**解决方案**

填写完整路径：OpenAI 音频接口用 `/v1/audio/speech`；使用对话接口合成音频的服务商
（如 MiMo 类）用 `/v1/chat/completions`。

**验证方式**

日志出现「在线TTS合成成功」，并能听到声音。

---

## 5. STT 录音失败：LineUnavailableException（设备不支持 16kHz）

`STT` `设备` `音频`

某些麦克风或驱动不支持固定 16000Hz 录音。

**日志关键字**

```text
LineUnavailableException
PCM_SIGNED 16000.0 Hz ... not supported
启动录音失败
```

**根因**

系统音频输入设备不支持该采样率或字节序，常见于蓝牙耳机、虚拟声卡、远程桌面音频等场景。

**解决方案**

在系统设置里切换输入设备；或把 `sampleRate` 改成 `44100` / `48000`（识别输入仍会按 16kHz 处理）。
手机端建议改用在线语音识别。

**验证方式**

按下语音键出现「录音中」，松开后出现「识别结果」。

---

## 6. Dedicated Server 崩溃：invalid dist DEDICATED_SERVER

`Server` `Dist` `崩溃`

专用服务端加载到了客户端专属类，被 Forge 拦截。

**日志关键字**

```text
Attempted to load class net/minecraft/client/... for invalid dist DEDICATED_SERVER
net/minecraft/client/Minecraft
KeyMapping
```

**根因**

服务端环境不能加载 `net.minecraft.client.*`。模组与 Verity 本体都需要在服务端路径上把客户端类隔离开。

**解决方案**

纯客户端模组不要装到专用服务端；或改用带服务端兼容处理的版本。自己编译时需要用
`DistExecutor` / `@OnlyIn` 做隔离。

**验证方式**

服务端完成模组加载并进入 `Done`，不再出现 invalid dist 堆栈。

---

## 7. Minecraft 登录鉴权 401：Failed to verify authentication

`Minecraft` `鉴权` `启动器`

这是启动器或账号 token 的问题，与模组本身无关，但会影响 Realms、皮肤等。

**日志关键字**

```text
Failed to verify authentication
InvalidCredentialsException: Status: 401
```

**根因**

启动器 token 失效、使用离线账户，或网络原因导致 Mojang 认证失败。

**解决方案**

重新登录启动器账号、刷新 token；必要时更换登录方式或检查网络。

**验证方式**

认证错误消失，Realms / 皮肤恢复正常。

---

## 8. Mixin 警告：yacl.mixins.json does not specify minVersion

`Warn` `Mixin` `YACL`

**通常可以忽略**，不是启动失败的原因。

**日志关键字**

```text
yacl.mixins.json does not specify minVersion
```

**建议**

如果确实出现混入异常或兼容性问题，再考虑升级 YACL 或相关模组版本。

---

## 9. Forge 提示：missing mods.toml file

`Warn` `Forge`

**通常可以忽略**，常见于 Forge 扫描自身库 jar 时的输出。

**日志关键字**

```text
missing mods.toml file
```

**验证方式**

只要后面没有跟着 `LoadingFailedException`，一般不影响运行。

---

## 10. 重启游戏后连不上 AI API，重新配对才恢复

`云端` `配置` `Android` `FCL` `cloud.enable`

重启后对话报连不上、进配置界面重新配对（或重新授权）就能恢复，完全退出再启动又复现。

**这不是密钥失效，而是开关被关掉了。** 重新配对的过程会顺带把 `cloud.enable` 打开，
所以看起来像「配对修好了」。

凭据存在两个地方，会不同步：

| 文件 | 内容 |
|---|---|
| `config/verity_extended/cloud-credentials.json` | api-key（配对时写入） |
| `config/verity_extended-common.toml` | `[cloud] enable`（总开关） |

开关一旦是 `false`，`CloudConfigManager.start()` 直接返回，云端线程根本不启动。
而配置界面可能仍显示「已授权」——因为它读的是凭据文件。

**日志关键字**

```text
云端配置线程未启动: cloud.enable=false 但凭据仍存在
```

看到这句就是本问题，不用再查别处。

**根因**

Forge 写 TOML 不是原子操作，进程被强杀会留下半截文件，`cloud.enable` 被读成默认值 `false`。

**Android / FCL / Pojav 上高发**：手机端进程被强杀是常态（启动器退出方式、系统回收后台），
所以这个坑在 PC 上偶发、在手机上能稳定复现。

4.5.2 的防护是「启动时检查 TOML 缺不缺键，缺了用快照恢复」——
但**文件没缺键、只是 `enable` 的值变成 false 时，这个检查是过得去的**，恢复不了。

**解决方案**

- **5.0 及以上**：已修复，无需操作。三层守卫：保存前后比对配置项数量（骤降 >10% 回滚）、
  JVM 关闭钩子再校验一次、快照恢复门槛放宽到键数 90%。另外保存只写用户改动过的项，
  不再整棵树重写——这才是写坏的根源。
- **4.5.2 / 4.5.3**：打开 `config/verity_extended-common.toml`，把 `[cloud]` 下的
  `enable = false` 改成 `true` 存盘；或进配置界面把云端开关重新打开。
  能撑到下次写坏为止，建议升级。

**验证方式**

重启游戏后，日志应出现：

```text
云端配置线程启动: generation=1, cloud.enable=true, 凭据存在=true
```

`凭据存在=false` 说明是另一个问题（凭据文件丢了或被清除授权），不是本条目。

---

## 反馈问题时请附上

- 完整日志（`latest.log`），不要只截一行
- 模组版本与 Verity 版本
- 复现步骤：做了什么、期望什么、实际发生了什么
- 如果是配置问题，附上相关配置项（**请先抹掉 API 密钥**）
