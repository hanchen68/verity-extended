package com.hanchen.veritycustomapi.cloud;

public final class CloudRuntimeReload {
    private static volatile Runnable callback;

    private CloudRuntimeReload() {
    }

    public static void set(Runnable value) {
        callback = value;
    }

    public static void clear() {
        callback = null;
    }

    public static void notifyApplied() {
        Runnable current = callback;
        if (current != null) current.run();
    }
}
