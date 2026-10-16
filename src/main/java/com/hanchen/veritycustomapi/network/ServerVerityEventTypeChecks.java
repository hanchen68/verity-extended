package com.hanchen.veritycustomapi.network;

public final class ServerVerityEventTypeChecks {
    private ServerVerityEventTypeChecks() {
    }

    public static boolean isValidType(int type) {
        return type >= 0 && type <= 8;
    }
}
