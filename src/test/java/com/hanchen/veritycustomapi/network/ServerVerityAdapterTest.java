package com.hanchen.veritycustomapi.network;

public final class ServerVerityAdapterTest {
    private ServerVerityAdapterTest() {
    }

    public static void main(String[] args) {
        ServerVerityMemoryContext.clear();
        require("fallback".equals(ServerVerityMemoryContext.current("fallback")),
                "memory context fallback changed without an active request");
        require(ServerVerityEventTypeChecks.isValidType(0), "ambient event type 0 rejected");
        require(ServerVerityEventTypeChecks.isValidType(8), "ambient event type 8 rejected");
        require(!ServerVerityEventTypeChecks.isValidType(9), "invalid event type accepted");
        System.out.println("Server Verity multiplayer adapter checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
