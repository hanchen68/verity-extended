package com.hanchen.veritycustomapi.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 自主状态包的编解码与客户端缓存规则自检。
 *
 * <p>为什么值得单独测：状态包是「界面显示是否可信」的唯一来源。
 * 编码/解码字段错位会让「已关闭」显示成「运行中」，而过期状态不清掉
 * 会让菜单一直显示上一个存档里同伴做过的事——两种都比没有状态更糟。
 */
public final class AutonomyStateProtocolTest {
    private AutonomyStateProtocolTest() {
    }

    public static void main(String[] args) {
        roundTrip();
        metadataIsBounded();
        staleSnapshotsAreNotTrusted();
        forgetWorks();
        System.out.println("自主状态协议检查通过");
    }

    private static void roundTrip() {
        AutonomyStatePayload source = new AutonomyStatePayload(42, true, false,
                "FOLLOW", "正在跟随主人", "等待模型决策");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        AutonomyStatePayload.write(source, buffer);
        AutonomyStatePayload decoded = AutonomyStatePayload.read(buffer);
        assertTrue(decoded.entityId() == 42, "实体 id 解码错误");
        assertTrue(decoded.enabled() && !decoded.suspended(), "开关状态解码错误");
        assertEquals("FOLLOW", decoded.intent());
        assertEquals("正在跟随主人", decoded.goal());
        assertEquals("等待模型决策", decoded.note());
    }

    private static void metadataIsBounded() {
        // 超长字符串必须被写端截断或拒绝，而不是把整段塞进网络包
        String longText = "x".repeat(5000);
        boolean threw = false;
        try {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            AutonomyStatePayload.write(new AutonomyStatePayload(1, true, false, longText, longText, longText), buffer);
        } catch (RuntimeException expected) {
            threw = true;
        }
        assertTrue(threw, "超长字段应被拒绝，避免无界网络包");
    }

    private static void staleSnapshotsAreNotTrusted() {
        com.hanchen.veritycustomapi.autonomy.ClientAutonomyState.clear();
        assertTrue(com.hanchen.veritycustomapi.autonomy.ClientAutonomyState.get(7) == null,
                "没有快照时应返回 null，调用方据此显示状态未知");
        com.hanchen.veritycustomapi.autonomy.ClientAutonomyState.accept(
                new AutonomyStatePayload(7, true, false, "IDLE", "", ""));
        assertTrue(com.hanchen.veritycustomapi.autonomy.ClientAutonomyState.get(7) != null,
                "刚收到的快照应可读取");
        assertTrue(com.hanchen.veritycustomapi.autonomy.ClientAutonomyState.get(7).enabled(),
                "开关状态丢了");
    }

    private static void forgetWorks() {
        com.hanchen.veritycustomapi.autonomy.ClientAutonomyState.clear();
        com.hanchen.veritycustomapi.autonomy.ClientAutonomyState.accept(
                new AutonomyStatePayload(9, true, false, "IDLE", "", ""));
        com.hanchen.veritycustomapi.autonomy.ClientAutonomyState.forget(9);
        assertTrue(com.hanchen.veritycustomapi.autonomy.ClientAutonomyState.get(9) == null,
                "实体卸载后必须忘掉它的状态");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }
}
