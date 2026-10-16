package com.hanchen.veritycustomapi.debug;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.OllamaProxyServer;
import net.minecraft.server.MinecraftServer;

public final class RuntimeDiagnostics {
    private static volatile long nextLogAtNanos = 0L;

    private RuntimeDiagnostics() {}

    public static void onServerTick(MinecraftServer server, int actionQueueSize) {
        if (!ConfigManager.isRuntimeMetricsEnabled()) return;
        if (server == null) return;

        long now = System.nanoTime();
        if (now < nextLogAtNanos) return;

        long intervalSeconds = Math.max(1, ConfigManager.getRuntimeMetricsIntervalSeconds());
        nextLogAtNanos = now + intervalSeconds * 1_000_000_000L;

        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long total = rt.totalMemory();
        long max = rt.maxMemory();

        int threads = Thread.getAllStackTraces().size();
        int inFlight = OllamaProxyServer.getInFlightRequestCount();
        long totalRequests = OllamaProxyServer.getTotalRequestCount();

        OllamaProxyMod.LOGGER.info(
                "RuntimeMetrics used={}MB total={}MB max={}MB threads={} actionQueue={} proxyInFlight={} proxyTotal={}",
                toMb(used),
                toMb(total),
                toMb(max),
                threads,
                actionQueueSize,
                inFlight,
                totalRequests
        );
    }

    private static long toMb(long bytes) {
        return bytes / 1024 / 1024;
    }
}

