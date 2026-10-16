package com.hanchen.veritycustomapi.network;

public final class ServerHorrorEventServiceTest {
    private ServerHorrorEventServiceTest() {
    }

    public static void main(String[] args) {
        long[] history = new long[9];
        history[2] = 1000L;
        require(ServerHorrorEventService.chooseType(history, 1001L, 2) != 2,
                "recent event type was selected before cooldown");
        require(ServerHorrorEventService.chooseType(history, 31001L, 2) == 2,
                "event type was not reusable after cooldown");
        require(ServerHorrorEventService.chooseType(new long[9], 100L, 4) == 4,
                "fresh event type selection was unstable");
        System.out.println("Server horror event selection checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
