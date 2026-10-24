# AGENTS.md — 给 AI 的项目入口

> 先读这一份，再动手。这里只写「不读就会踩坑」的信息，细节在 `docs/`。

## 这是什么

**Verity 扩展增强（`verity_extended`）5.0** —— Minecraft 1.20.1 / Forge 47.3+ 的客户端+服务端模组，
基于第三方模组 **Verity JE 6.0.0-beta.9**（实体 `varmite.verity.entity.verity.VerityEntity`）做扩展。

一句话定位：**它不重写 Verity，而是在它的边界上加东西**——AI 对话链路、云端配置、语音/朗读、
好感与送礼、恐怖事件、MMD 播放、主动行为（溜达/护主/护主打怪），以及若干第三方模组的兼容层。

## 五分钟建立坐标

| 你想找的东西 | 去哪 |
|---|---|
| 模组入口 / 注册 | `OllamaProxyMod.java`（`@Mod("verity_extended")`） |
| AI 对话与提示词 | `ConfigManager.java`（1 万行级，人设/路由/额度都在这）、`compat/OriginalVerityChatBridge.java` |
| 与原版 Verity 的耦合 | `mixin/`（35 个已声明类：30 注入 + 5 Accessor）+ `compat/`（34 个反射封装） |
| 配置定义 | `config/ModConfig.java`（两个 spec） |
| 配置界面 | `config/NativeOllamaProxyConfigScreen.java`（自绘，非 Cloth） |
| 云端下发 | `cloud/CloudConfigManager.java` + `config/CloudOverrides.java` |
| 网络包 | `network/*Network.java`（6 条通道） |
| 客户端表现 | `client/`（35 个）、`mixin/Verity*Render*.java` |

## 硬性约束（违反会直接崩或静默失效）

1. **不要改 `src/main/java/.../verity-src原版`、`verity编译`、`verity-cn版编译`** ——
   它们被 `build.gradle` 的 `sourceSets.exclude` 排除，只是反编译参考。发行包里也不该出现。
2. **不要直接调 `ModConfig.COMMON_SPEC.save()` / `CLIENT_SPEC.save()`** ——
   必须走 `ConfigManager.saveCommonConfig()` / `saveClientConfig()`，否则云端覆盖值会被写进玩家 TOML。
3. **不要新增会写 TOML 的代码**；现有的写入都集中在 `CloudOverrides.saveSpec()`，
   它带「保存前还原本地值 + 保存后回写覆盖值 + 生成 `.snapshot`」三步。
4. **`cloud` 配置段永远不许被云端覆盖**（`CloudOverrides.FORBIDDEN_ROOT`），
   否则等于把引导端点交给远端。
5. **默认（common）代码里不许 import 客户端类**；需要时用
   `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ...)` 或放到 `client/` 包。
6. **新增 mixin 必须同时在 `mixins.verity_extended.json` 登记**；
   碰到客户端类放 `"client"` 数组，服务端类放 `"mixins"`。
7. **对第三方/原版内部的注入优先 `require = 0`**，并让功能可降级；
   唯一例外是核心渲染/交互，那些允许硬失败以便尽早暴露问题。
8. **lambda 方法名（`lambda$queryAI$4` 之类）是编译器产物**，改上游版本后必须重新核对。

## 构建与验证

```bash
# 首次：展开原版 Verity（fat jar 含 dev.langchain4j，不展开会编译失败）
powershell -ExecutionPolicy Bypass -File tools/prepare-lib.ps1 -JarPath "<path>/verity-6.0.0-beta.9.jar"

./gradlew compileJava          # 快速编译
./gradlew clean productionJar  # 产出可分发 jar（见 docs/06）
```

产物：`build/libs/Verity扩展增强-<version>.jar`，版本号在 `build.gradle` 的 `version`。
测试任务有 30 个（`./gradlew tasks` 里 `resourceSecurityTest`、`cloudOverridesTest`、`mixinPackageHygieneTest` 等），
改配置/云端/mixin 相关逻辑后建议至少跑对口的那个。

## 交付规范（每个版本必做，细则见 `docs/09`）

**没有更新日志、没有状态报告的改动，不算交付。**

```text
1. 改代码 → 2. compileJava → 3. 跑对口自检
4. 升 build.gradle version（补丁位 / 次版本位 / 主版本位，规则见 docs/09#1）
5. 在根 CHANGELOG.md 顶部写条目（结构固定，见 docs/09#2；不要新建 CHANGELOG_x.md）
6. clean productionJar → 7. 算 SHA-256 回填 CHANGELOG 与 release/SHA256.txt
8. 删旧 jar 再放新 jar（同 modId 只能一个）→ 9. 实机启动确认日志无降级
10. 输出下面的状态报告
```

交付后**必须原样输出**这段（字段不能省，没有的写「无」）：

```markdown
## 交付：v<版本>（<YYYY-MM-DD>）

**产物**
- 文件：`build/libs/Verity扩展增强-<版本>.jar`（已部署到 `<路径>`）
- SHA-256：`<64 位小写>`
- 构建：`./gradlew clean productionJar` 通过

**本版改动**
- 新增：……
- 修复：……
- 兼容：……

**配置变更**
- 新增键 / 变更键 / 移除键（没写「无」）

**兼容性**
- 适配基准：Verity JE `6.0.0-beta.9`；注入点与反射成员核对：通过 / N 处待适配
- 网络协议：变 / 未变
- 依赖区间：Forge `[47.3,48)`（编译基准 47.3.0）、GeckoLib `[4.4,)`

**已知问题**
- ……（结构性限制照 docs/08 写，不要写「暂无」糊过去）

**验证方式**
- 命令：……
- 日志关键行：……
```

另外附一句给玩家的三句话摘要：**这版加了什么 / 修了什么 / 用户要做什么**。

## 常见任务 → 看哪

| 任务 | 文档 |
|---|---|
| 加一个配置项并出现在界面上 | `docs/07-扩展指南.md#加配置项` |
| 加一个 `/命令` | `docs/07-扩展指南.md#加命令` |
| 加一个 AI 工具 | `docs/07-扩展指南.md#加-ai-工具` |
| 注入原版 Verity 的某个方法 | `docs/03-Mixin与兼容层.md` |
| 兼容一个新的第三方模组 | `docs/07-扩展指南.md#加兼容层` |
| 加网络包 | `docs/05-网络与命令.md` |
| 线上问题排查 | `docs/06-构建与调试.md#排障表` |
| 交付、写更新日志、输出状态报告 | `docs/09-交付与版本规范.md` |

## 启动日志速查（排查第一步）

```text
[MixinPlugin] 原版回复发送结构：旧版（AiManager.lambda$queryAI$4，6.0/6.1）
[MixinPlugin] PatPat 兼容目标已检测到
[StateAccess] 原版 Verity 状态访问已就绪，跟随开关=可用，恶魔形态=可用
[汉化] 原版 Verity 汉化加载，共 N 条
云端配置线程启动: generation=N, cloud.enable=true, 凭据存在=true
云端配置覆盖: 下发 N 项，生效 M 项
```

出现下列任意一条，说明进入了降级路径，需要看 `docs/06` 对照：

```text
未检测到原版 Verity 模组
sendReplyPart 与 lambda$queryAI$4 都不在 AiManager 里
检测到 xxx.toml 有重复项 / 看起来被写坏了
云端快照不含内置线路密钥
```

## 已知限制（别当成 bug 去修）

- Verity 本体是**会看人的 billboard 贴图**（`SphereEntityRenderer`），没有骨骼和手，
  所以「手持物品」是客户端渲染层模拟，不是真实装备槽。
- 《The Shooting Star》是**可选依赖**，未安装时星之遥控器相关功能静默关闭。
- 原版 Verity 的 `VerityState.isMonstrous` 在 6.0.0-beta.9 已移除，代码走实体同步数据兜底。
- 云端**内置线路密钥不落盘**，重启后需等一次同步。

完整清单：`docs/08-已知限制.md`。
