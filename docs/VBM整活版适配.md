# VBM 预览版（`verity-6.0.0-整活demo.jar`）专属适配

> **适用对象**：`displayName = "Verity JE (VBM Preview)"` 的 6.0.0 构建
> **判定方式**：字节扫描 `varmite/verity/vbm/VbmEvents.class` 是否存在
> （`VerityStructureDetector.hasVbmMoodSystem()`，**不用 `Class.forName`**）

## 一、这一版多了什么

和正式版 6.0.0 相比，多出 `varmite/verity/vbm/` 一整套（26 个类）：

| 类 | 作用 |
|---|---|
| `KarmaService` | 好感度：`change(level, delta, source)` 直接写 `WorldSpawnData.verityKarma` |
| `MoodBand` | 好感分档：GOOD / MIDDLE / BAD / TERRIBLE，各有默认表情 |
| `MoodService` | 心情过渡、强制表情、夜间情绪、心情弧线 |
| `MoodPrompts` | **把心情写进 AI 提示词**（`AiAPI` 里已调用 `directive(...)`） |
| `StressService` | 压力值（孤独、受伤累加，随时间衰减） |
| `FlatService` | 压扁 / 充气 / 飞盘（整活玩法），压扁时 `VerityEntity.isFlat()` 为 true |
| `GiftService` | Verity 会**自己捡起掉在附近的东西**当礼物，按价值加好感、有毒性判定 |
| `VbmEvents` | 服务端 tick / 受伤 / 聊天 / 交互的事件总入口 |
| `PatPatHookMixin` | 只处理「压扁状态下的摸头安慰」，不接管普通摸头 |

## 二、扩展怎么让位（检测到才生效）

| 能力 | 正式版 6.0.0 | VBM 预览版 |
|---|---|---|
| 送礼（潜行 + Q 丢物品） | 扩展接管：取消掉落 + 加好感 | **让位**：不拦截，物品正常落地，由原版 `GiftService` 捡起 |
| 心情 / 压力 / 好感带 | 原版没有 | **原版负责**，扩展不重复注入提示词（`MoodPrompts` 已经在做） |
| 压扁状态 | 不存在 | **站头 / 骑乘直接拒绝**，理由「我现在被压成一张饼了……」 |
| 摸头回应 | 扩展的 AI 台词 | **保留**（原版那个 hook 只管压扁时的安慰，不冲突） |
| 每日好感上限 | `ModEvents.updateAndSyncKarma` 上截断 | 保留（VBM 自己那条 `KarmaService.change` 不走这个方法，见下） |

## 二·五、渲染签名变了（这一版必踩）

`SphereEntityRenderer.render(...)` 里画球体的调用从 **18 参** 换成了 **21 参**：

```text
旧：SphereRenderHelper.renderEntityBillboard(..., DDDDDD, F, F, I, I, I, I, F, F)
新：SphereRenderHelper.renderEntityBillboard(..., DDDDDD, F, F, F, I, I, I, I, F, F, F, I, F)
```

扩展原来那条 `@Redirect` 只认 18 参，找不到调用点就抛 `InjectionError`；
而渲染器是**注册阶段**就被变换的，所以表现是**游戏直接崩在启动**，连主菜单都进不去。

修法（`SphereRendererTalkingStateMixin`）：

1. 原 Redirect 加 `require = 0`——基础版照常命中，这一版安静跳过；
2. 新增 `verityExtended$applyAudioLevelVbm`，按 21 参签名接住，把前 12 个参数原样转给
   18 参重载（这个重载在这一版里**仍然存在**）。多出来的 3 个参数是压扁/贴脸用的，扩展不参与。
   → Q弹、体型缩放、语音弹动、摸头缩放在这一版上**照旧生效**，只有压扁时的贴脸细节归原版。

---

## 三、已知未覆盖

**VBM 的好感改动不走 `updateAndSyncKarma`**：`KarmaService.change` 直接写
`WorldSpawnData.verityKarma` 再调 `ModEvents.syncKarma(server)`，
绕开了 `VerityKarmaCapMixin` 的注入点。所以在这一版上，
**由 VBM 自己（送礼 / 压力反应 / 心情）产生的好感不受「每日上限」约束**。

没有强行补这一刀的原因：VBM 的好感是**世界级**的（`WorldSpawnData.verityKarma`，全服共用），
而扩展的每日上限是**按世界算额度**的玩家保护措施，两者语义对不上；
强行在 `KarmaService.change` 上截断会把原版的心情带过渡一起掐掉。
如果确实需要，正确做法是给 `KarmaService.change` 单独写一个 mixin（同样用
`hasVbmMoodSystem()` 门控），在 HEAD 处按剩余额度决定是否 cancel。

## 四、验证方法

启动日志里应该出现（`[MixinPlugin]` 前缀）：

```text
[MixinPlugin] 检测到 VBM 预览版（整活 demo）：心情提示词与好感注入由原版负责，扩展不再重复；
送礼让位给原版 GiftService，压扁状态下不参与站头/骑乘
```

游戏内验证：

1. **送礼**：潜行 + Q 丢一个物品 → 物品**应该落地**（不再凭空消失），随后 Verity 自己走过来捡；
2. **压扁**：被重物砸成饼后按 H → 提示「我现在被压成一张饼了，站不住……」；
3. **站头 / 骑乘**：压扁恢复后照常可用。

## 五、没装这一版时

`hasVbmMoodSystem()` 返回 false，上面所有分支都不进入，
行为与正式版 6.0.0 完全一致——**不会因为这段适配影响普通玩家**。
