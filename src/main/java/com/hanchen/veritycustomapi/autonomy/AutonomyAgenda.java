package com.hanchen.veritycustomapi.autonomy;

import com.google.gson.*;
import com.hanchen.veritycustomapi.compat.VeritySay;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import varmite.verity.entity.verity.VerityEntity;
import java.util.*;

/** Durable intention and sequential executor. A model promise is never an action result. */
public final class AutonomyAgenda {
    private static final String KEY = "verity_ext_agenda";
    private static final Map<VerityEntity, Data> DATA = Collections.synchronizedMap(new WeakHashMap<>());
    static final class Data {
        String goal = "", completion = "", result = "尚无执行结果", dimension = "";
        String state = "NEEDS_PLAN", waiting = "";
        JsonArray steps = new JsonArray();
        JsonArray destinations = new JsonArray();
        int cursor, completed, failures;
        long since, lastSpeech, due;
        /** 最近一次真正有进展的 tick；用来判断"卡住"。 */
        long lastProgress;
        String skill = "", pendingSkill = "";
    }
    private AutonomyAgenda() {}
    private static Data data(VerityEntity v) {
        Data d = DATA.get(v);
        if (d != null) return d;
        d = new Data();
        CompoundTag t = v.getPersistentData().getCompound(KEY);
        d.goal = clip(t.getString("goal"),240); d.completion = clip(t.getString("completion"),300);
        d.result = clip(t.getString("result"),1800); d.completed = Math.max(0,t.getInt("completed"));
        d.failures = Math.max(0,t.getInt("failures")); d.dimension = t.getString("dimension");
        d.lastSpeech = t.getLong("lastSpeech"); d.since = t.getLong("since");
        // Reload invalidates navigation and live jobs, not the persistent motivation.
        // Never replay an uncertain write after reload: request a new plan with explicit uncertainty.
        if (!d.goal.isBlank()) d.result += "；世界重新加载，未执行步骤与未验证作业不可视为完成，需观察后重规划。";
        d.dimension = v.level().dimension().location().toString();
        DATA.put(v,d); return d;
    }
    public static JsonObject snapshot(VerityEntity v) {
        Data d = data(v); JsonObject o = new JsonObject();
        o.addProperty("goal",d.goal); o.addProperty("completionEvidenceRequired",d.completion);
        o.addProperty("state",d.state); o.addProperty("step",d.cursor); o.addProperty("steps",d.steps.size());
        o.addProperty("completedActions",d.completed); o.addProperty("failures",d.failures);
        o.addProperty("lastActualResult",d.result); o.addProperty("waitingFor",d.waiting);
        o.addProperty("stalled", stalled(v));
        return o;
    }
    public static boolean executing(VerityEntity v) {
        Data d = data(v); return d.state.equals("EXECUTING") && (!d.waiting.isBlank() || d.cursor < d.steps.size());
    }

    /**
     * 卡住判定：某一步等待太久没有任何进展。
     *
     * <p><b>为什么必须有</b>：{@code executing()} 为真时决策服务不会再发请求。
     * 一旦某个等待永远等不到结果（材料耗尽、作业丢失、导航静默），
     * 整条链路就会停在一步上，表现就是「一个任务卡住不动，别的什么都不做」。
     */
    public static boolean stalled(VerityEntity v) {
        Data d = data(v);
        if (!executing(v)) return false;
        long now = v.level().getGameTime();
        return AutonomyStall.expired(d.waiting, now - d.lastProgress);
    }

    private static void touch(VerityEntity v, Data d) {
        d.lastProgress = v.level().getGameTime();
    }
    public static void install(VerityEntity v, AutonomyDecision decision, Map<String,Vec3> candidates) {
        Data d = data(v);
        if (!decision.goal().isBlank() && !decision.goalState().equals("DONE")) {
            if (!decision.goal().equals(d.goal)) {
                d.completed=0; d.failures=0; d.since=v.level().getGameTime();
                com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.info("[Autonomy] entity={} newGoal={}",v.getId(),decision.goal());
            }
            d.goal = decision.goal(); d.completion = decision.completion();
        }
        if (decision.goalState().equals("CANCEL")) {
            d.steps = new JsonArray(); d.waiting=""; d.state="CANCELLED";
            d.result="模型放弃了目标；这不是成功完成。"; save(v,d); return;
        }
        if (decision.goalState().equals("DONE")) {
            // A terminal result from a deterministic executor is required, not a success sentence.
            if (AutonomyEvidence.mayReview(d.completed,d.failures,!d.waiting.isBlank(),d.cursor>=d.steps.size())) {
                d.state="REVIEWED"; d.result += "；模型据此结束本轮目标，请按实际证据评价，不等于任意验收条件都已满足。";
                learnIfNeeded(v,d);
            } else { d.state="NEEDS_PLAN"; d.result += "；模型宣告完成但缺执行证据，未认可。"; }
            save(v,d); return;
        }
        if (decision.steps().isEmpty()) {
            if (d.goal.isBlank()) { d.goal = decision.reason(); d.since=v.level().getGameTime(); }
            d.steps = new JsonArray();
            if (!decision.action().equals("KEEP")) {
                JsonObject step = new JsonObject(); step.addProperty("action",decision.action());
                if (!decision.candidate().isBlank()) step.addProperty("candidate",decision.candidate());
                step.addProperty("reason",decision.reason());
                if (!decision.tool().isBlank()) step.addProperty("tool",decision.tool());
                step.add("args",decision.args().deepCopy()); d.steps.add(step);
            }
        } else d.steps=decision.steps().deepCopy();
        if (!decision.useSkill().isBlank()) {
            var skill=AutonomySkills.find(v,decision.useSkill());
            if (skill==null || skill.steps().isEmpty()) { d.state="NEEDS_PLAN"; d.result="引用了不存在的技能："+decision.useSkill(); save(v,d); return; }
            d.steps=skill.steps().deepCopy(); d.skill=skill.name();
        } else d.skill="";
        if (!decision.skillName().isBlank()) d.pendingSkill=decision.skillName();
        d.destinations = new JsonArray();
        for (var entry:candidates.entrySet()) {
            JsonObject p=new JsonObject(); p.addProperty("id",entry.getKey());
            p.addProperty("x",entry.getValue().x); p.addProperty("y",entry.getValue().y); p.addProperty("z",entry.getValue().z);
            d.destinations.add(p);
        }
        d.cursor=0; d.waiting=""; d.dimension=v.level().dimension().location().toString();
        touch(v, d);
        com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.info("[Autonomy] entity={} planSteps={} firstAction={}",
                v.getId(), d.steps.size(), d.steps.isEmpty()?"NONE":d.steps.get(0).getAsJsonObject().get("action").getAsString());
        d.state=d.steps.isEmpty()?"NEEDS_PLAN":"EXECUTING";
        save(v,d);
    }
    public static void tick(VerityEntity v, ServerPlayer owner) {
        Data d=data(v); var s=VerityAutonomy.state(v); long now=v.level().getGameTime();
        if (stalled(v)) {
            feedback(v, false, "这一步太久没有进展（" + d.waiting + "），放弃并重新规划");
            return;
        }
        if (!s.enabled || s.suspended || s.waitUntil>now || owner==null) return;
        if (!com.hanchen.veritycustomapi.config.ModConfig.localToolsEnabled()) return;
        if (owner.level()!=v.level() || !com.hanchen.veritycustomapi.network.ServerVerityRegistry.owns(owner,v)) return;
        if (!d.dimension.isBlank() && !d.dimension.equals(v.level().dimension().location().toString())) {
            feedback(v,false,"维度改变，旧步骤已取消，保留目标重新观察"); return;
        }
        if (!d.state.equals("EXECUTING")) return;
        if (!d.waiting.isBlank()) {
            if (d.waiting.equals("SMELT")) {
                if (com.hanchen.veritycustomapi.body.VeritySmelting.smelting(v)) return;
                String done = com.hanchen.veritycustomapi.body.VeritySmelting.poll(v);
                feedback(v, done != null && !AutonomyEvidence.knownFailure(done),
                        done == null ? "熔炼中断，未完成" : done);
                return;
            }
            if (d.waiting.equals("BLUEPRINT")) {
                if (com.hanchen.veritycustomapi.body.VerityBlueprint.building(v)) return;
                String done = com.hanchen.veritycustomapi.body.VerityBlueprint.poll(v);
                feedback(v, done != null && !AutonomyEvidence.knownFailure(done),
                        done == null ? "施工中断，未完成" : done);
                return;
            }
            if (d.waiting.equals("SHELTER")) {
                if (com.hanchen.veritycustomapi.body.VerityShelter.building(v)) return;
                String done = v.getPersistentData().getString("verity_ext_shelter_result");
                v.getPersistentData().remove("verity_ext_shelter_result");
                feedback(v, !done.isBlank(), done.isBlank() ? "盖房中断，未完成" : done);
                return;
            }
            if (d.waiting.equals("MINE")) {
                String mined = com.hanchen.veritycustomapi.body.VerityMining.poll(v);
                if (mined == null) {
                    if (now - d.due > 20L * 45L) feedback(v, false, "挖掘超时，未完成");
                    return;
                }
                feedback(v, !AutonomyEvidence.knownFailure(mined), mined);
                return;
            }
            if (d.waiting.equals("TOOL")) {
                if (now-d.due>20L*600L) feedback(v,false,"作业终态长期未返回，结果未知，停止后重规划");
                return; // Exact job-id terminal result delivered by AutonomyActions.poll.
            }
            if (now-d.due>20L*45L) { feedback(v,false,"步骤执行超时，未完成"); return; }
            if (d.waiting.equals("TIMED") && now>=d.due) {
                var step = AutonomyDecision.step(d.steps.get(d.cursor).getAsJsonObject());
                boolean ready = !step.action().equals("COMPANION") || v.distanceToSqr(owner) <= 36;
                if (ready) feedback(v,true,"已实际维持本步骤要求的陪伴/等待状态，非物品或建筑成果");
            }
            return;
        }
        if (d.cursor>=d.steps.size()) { d.state="NEEDS_PLAN"; save(v,d); return; }
        if (VerityAutonomy.busy(v) || s.inFlight || now<s.retryAt) return;
        AutonomyDecision step=AutonomyDecision.step(d.steps.get(d.cursor).getAsJsonObject());
        Map<String,Vec3> choices=new HashMap<>();
        for(JsonElement e:d.destinations) { var p=e.getAsJsonObject(); choices.put(p.get("id").getAsString(),new Vec3(p.get("x").getAsDouble(),p.get("y").getAsDouble(),p.get("z").getAsDouble())); }
        if (step.action().equals("PRIMITIVE")) {
            // 原语是「手」，当帧执行并立即得到真实结果，不需要等待
            d.waiting="IMMEDIATE"; touch(v, d); save(v,d);
            try {
                String result=AutonomyDecisionService.executeStep(v,owner,step,choices);
                if ((step.primitive().equals("break_block") && result.startsWith("开始挖"))
                        || (step.primitive().equals("fell_tree") && result.startsWith("开始砍树"))
                        || (step.primitive().equals("shelter") && result.startsWith("开始盖庇护所"))
                        || (step.primitive().equals("smelt") && result.startsWith("开始烧"))
                        || (step.primitive().equals("blueprint") && result.startsWith("开始照"))) {
                    // 这些都要等真实完成，不能当帧算成功
                    d.waiting = step.primitive().equals("shelter") ? "SHELTER"
                            : step.primitive().equals("smelt") ? "SMELT"
                            : step.primitive().equals("blueprint") ? "BLUEPRINT" : "MINE";
                    d.due=now; save(v,d); return;
                }
                feedback(v,!AutonomyEvidence.knownFailure(result),result);
            } catch (RuntimeException failure) { feedback(v,false,"原语拒绝："+failure.getMessage()); }
            return;
        }
        d.waiting=step.action().equals("SCOUT")?"MOVE":step.action().equals("TOOL")?"TOOL":"TIMED";
        touch(v, d);
        d.due=now+(d.waiting.equals("TIMED")?100:0); save(v,d);
        try {
            com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.debug("[Autonomy] entity={} step={}/{} action={} tool={}",v.getId(),d.cursor+1,d.steps.size(),step.action(),step.tool());
            String result=AutonomyDecisionService.executeStep(v,owner,step,choices);
            if (step.action().equals("TOOL")) {
                if (!com.hanchen.veritycustomapi.behavior.VerityMiningJob.isBusy(owner))
                    feedback(v,!AutonomyEvidence.knownFailure(result),result);
                else { d.result=clip(result,1800); save(v,d); }
            }
        } catch (RuntimeException failure) { feedback(v,false,"步骤拒绝："+failure.getClass().getSimpleName()); }
    }
    /** 只有整套步骤全部成功才落成技能；失败经验不写进技能库。 */
    private static void learnIfNeeded(VerityEntity v, Data d) {
        if (d.steps.size() < 2 || !d.skill.isBlank()) return;
        String name = d.pendingSkill.isBlank()
                ? "skill_" + Integer.toHexString(Math.abs((d.goal + d.completion).hashCode()))
                : d.pendingSkill;
        if (AutonomySkills.learn(v, name, d.goal + "｜" + d.completion, d.steps)) {
            com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.info("[Autonomy] entity={} learned skill={} steps={}",
                    v.getId(), name, d.steps.size());
            d.pendingSkill = "";
        }
    }
    public static void movementFeedback(VerityEntity v, boolean success, String result) {
        if (data(v).waiting.equals("MOVE")) feedback(v, success, result);
    }
    public static void feedback(VerityEntity v, boolean success, String result) {
        Data d=data(v);
        if (!d.state.equals("EXECUTING") || d.waiting.isBlank()) return;
        d.result=clip(result,1800); d.waiting=""; touch(v, d);
        if (!d.skill.isBlank()) AutonomySkills.record(v, d.skill, success);
        if (success) { d.completed++; d.cursor++; }
        else { d.failures++; d.state="NEEDS_PLAN"; d.steps=new JsonArray(); d.cursor=0; }
        if (d.cursor>=d.steps.size()) d.state="NEEDS_PLAN";
        VerityAutonomy.state(v).nextThinkAt=Math.min(VerityAutonomy.state(v).nextThinkAt,v.level().getGameTime()+20);
        save(v,d);
    }
    public static void interrupted(VerityEntity v,String reason) {
        Data d=data(v); d.steps=new JsonArray(); d.waiting=""; d.cursor=0;
        d.state="NEEDS_PLAN"; d.result=clip(reason,1800); save(v,d);
    }
    public static void maybeSpeak(VerityEntity v, ServerPlayer owner,String speech) {
        if (speech==null || speech.isBlank() || owner==null || owner.distanceToSqr(v)>16*16) return;
        Data d=data(v); long now=v.level().getGameTime();
        if (now-d.lastSpeech<2400 || !VerityAutonomy.state(v).enabled || VerityAutonomy.state(v).suspended) return;
        String line=com.hanchen.veritycustomapi.memory.ChatCompletionClient.sanitizeOutput(speech,180);
        if (line.isBlank()) return;
        // 原版有 startTalking：不调用只有聊天栏一行字，嘴不动，看着像系统提示
        v.startTalking(Math.max(30, Math.min(120, 20 + line.length() * 2)));
        VeritySay.to(owner,line); d.lastSpeech=now; save(v,d);
    }
    public static String describe(VerityEntity v) {
        Data d=data(v); return AutonomySkills.describe(v) + "；自主目标："+(d.goal.isBlank()?"正在形成":d.goal)+"；步骤 "+Math.min(d.cursor+1,d.steps.size())+"/"+d.steps.size()+"；"+d.state+"；实际结果："+clip(d.result,180);
    }
    public static void reset() { DATA.clear(); }
    private static void save(VerityEntity v,Data d) {
        CompoundTag t=new CompoundTag(); t.putString("goal",d.goal); t.putString("completion",d.completion);
        t.putString("result",d.result); t.putString("dimension",d.dimension); t.putInt("completed",d.completed);
        t.putInt("failures",d.failures); t.putLong("since",d.since); t.putLong("lastSpeech",d.lastSpeech);
        t.putString("state",d.state); t.putInt("cursor",d.cursor);
        // Keep a human-inspectable bounded plan; live paths/jobs deliberately require revalidation on reload.
        t.putString("steps",d.steps.toString()); v.getPersistentData().put(KEY,t);
    }
    private static String clip(String text,int max) { if(text==null)return ""; return text.substring(0,Math.min(max,text.length())); }
}
