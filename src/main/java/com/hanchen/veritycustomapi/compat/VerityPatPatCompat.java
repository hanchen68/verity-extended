package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.network.VerityPatNetwork;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;

/**
 * 客户端可选的桥接：调 PatPat 自己的实体压扁动画。
 * 这个类的签名里不出现任何 PatPat 类型，所以 PatPat 装不装都行。
 */
public final class VerityPatPatCompat {
    private static final String RENDERER_CLASS = "net.lopymine.patpat.client.render.PatPatClientRenderer";
    private static final String PACKET_CLASS = RENDERER_CLASS + "$PacketPat";
    private static final ResourceLocation VERITY_ID = new ResourceLocation("verity", "verity");
    private static volatile Method scaleMethod;
    private static volatile Method pattedEntityMethod;
    private static volatile Method replayModPacketMethod;
    /** 原版自带的抚摸通道（6.2+ 的 {@code PatC2SPacket.send}）；为 null 时走扩展自己的网络请求。 */
    private static volatile Method nativePatSender;
    private static volatile boolean resolved;
    private static volatile boolean failed;

    private VerityPatPatCompat() {
    }

    public static void applyScale(LivingEntity entity, PoseStack poseStack, float partialTick) {
        if (entity == null || poseStack == null || failed) return;
        resolve();
        // 新版原版自带缩放动画，再调一次会重复缩放，故只在缩放上让位，不影响触发。
        if (nativePatSender != null) return;
        Method method = scaleMethod;
        if (method == null) return;
        try {
            method.invoke(null, entity, poseStack, partialTick);
        } catch (Throwable error) {
            failed = true;
            OllamaProxyMod.LOGGER.warn("PatPat 的 Verity 抚摸动画桥接失败，本次会话已停用兼容层", error);
        }
    }

    public static void onClientPat(Object packet) {
        if (packet == null || failed || pattedEntityMethod == null) return;
        resolve();
        try {
            if (replayModPacketMethod != null && Boolean.TRUE.equals(replayModPacketMethod.invoke(packet))) return;
            Object target = pattedEntityMethod.invoke(packet);
            if (!(target instanceof LivingEntity entity) || !entity.isAlive()) return;
            if (!VERITY_ID.equals(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()))) return;
            // 新版原版自带抚摸通道，触发交给 PatC2SPacket（自带冷却、计数、缩放），不重复发请求；
            // 旧版没有该通道，仍走扩展自己的网络请求。
            if (nativePatSender != null) {
                nativePatSender.invoke(null, entity.getId());
                return;
            }
            VerityPatNetwork.sendRequest(entity.getId());
        } catch (Throwable error) {
            failed = true;
            OllamaProxyMod.LOGGER.warn("PatPat 的 Verity 抚摸对话桥接失败，本次会话已停用兼容层", error);
        }
    }

    public static boolean isAvailable() {
        resolve();
        if (failed || pattedEntityMethod == null) return false;
        return nativePatSender != null || (scaleMethod != null && replayModPacketMethod != null);
    }

    /**
     * 解析原版自带的抚摸通道（{@code PatC2SPacket.send(int)}）。
     * 原版 6.2 只做服务端处理、不检测抚摸，检测与发送由集成方负责，故不能让位，否则摸头无反应。
     */
    private static Method resolveNativePatSender(ClassLoader loader) {
        try {
            Class<?> packet = Class.forName("varmite.verity.network.PatC2SPacket", false, loader);
            return packet.getMethod("send", int.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method resolve() {
        if (resolved) return scaleMethod;
        synchronized (VerityPatPatCompat.class) {
            if (resolved) return scaleMethod;
            try {
                ClassLoader loader = VerityPatPatCompat.class.getClassLoader();
                Class<?> renderer = Class.forName(RENDERER_CLASS, false, loader);
                Class<?> packet = Class.forName(PACKET_CLASS, false, loader);
                scaleMethod = renderer.getMethod("scaleEntityIfPatted",
                        LivingEntity.class, PoseStack.class, float.class);
                pattedEntityMethod = packet.getMethod("pattedEntity");
                replayModPacketMethod = packet.getMethod("replayModPacket");
                // 新版原版自带抚摸通道，触发与缩放都交给它；
                // 但不能整体让位，原版不检测抚摸，让位会导致摸头无反应。
                nativePatSender = resolveNativePatSender(loader);
                if (nativePatSender != null) {
                    OllamaProxyMod.LOGGER.info(
                            "检测到原版抚摸通道（PatC2SPacket.send），触发交给原版、缩放也交给原版");
                } else {
                    OllamaProxyMod.LOGGER.info("检测到 PatPat，已启用原版 Verity 抚摸动画与对话反馈兼容");
                }
            } catch (ClassNotFoundException ignored) {
                OllamaProxyMod.LOGGER.info("未检测到 PatPat，跳过 Verity 抚摸动画兼容");
            } catch (Throwable error) {
                failed = true;
                OllamaProxyMod.LOGGER.warn("初始化 PatPat 的 Verity 兼容层失败", error);
            } finally {
                resolved = true;
            }
            return scaleMethod;
        }
    }
}
