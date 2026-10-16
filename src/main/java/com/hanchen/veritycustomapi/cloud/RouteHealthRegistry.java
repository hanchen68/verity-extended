package com.hanchen.veritycustomapi.cloud;

import java.util.HashMap;
import java.util.Map;

public final class RouteHealthRegistry {
    public enum FailureKind { NETWORK, TIMEOUT, SERVER, UNAVAILABLE, AUTHENTICATION, BILLING, PARAMETER, CANCELLED }
    public enum State { HEALTHY, COOLDOWN, PROBING }

    private final Map<Key, Entry> entries = new HashMap<>();

    public synchronized State state(String service, String tier, String routeId, long now, RoutePolicy policy) {
        Entry entry = entries.get(new Key(service, tier, routeId));
        if (entry == null) return State.HEALTHY;
        if (entry.state == State.COOLDOWN && now >= entry.cooldownUntil) {
            entry.state = State.PROBING;
        }
        return entry.state;
    }

    public synchronized boolean tryAcquire(String service, String tier, String routeId, long now, RoutePolicy policy) {
        State state = state(service, tier, routeId, now, policy);
        if (state == State.HEALTHY) return true;
        if (state == State.PROBING) {
            Entry entry = entries.get(new Key(service, tier, routeId));
            if (entry.probeClaimed) return false;
            entry.probeClaimed = true;
            return true;
        }
        return false;
    }

    public synchronized boolean isSelectable(String service, String tier, String routeId, long now, RoutePolicy policy) {
        return state(service, tier, routeId, now, policy) != State.COOLDOWN;
    }

    public synchronized void reportFailure(String service, String tier, String routeId, FailureKind kind, long now, RoutePolicy policy) {
        if (kind == null || kind == FailureKind.AUTHENTICATION || kind == FailureKind.BILLING
                || kind == FailureKind.PARAMETER || kind == FailureKind.CANCELLED) {
            return;
        }
        Key key = new Key(service, tier, routeId);
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        entry.failures++;
        entry.lastFailureAt = now;
        entry.probeClaimed = false;
        if (entry.failures >= policy.threshold()) {
            entry.state = State.COOLDOWN;
            entry.cooldownUntil = now + policy.healthIntervalMillis();
        }
    }

    public synchronized void reportSuccess(String service, String tier, String routeId, long now) {
        Entry entry = entries.computeIfAbsent(new Key(service, tier, routeId), ignored -> new Entry());
        entry.failures = 0;
        entry.lastSuccessAt = now;
        entry.cooldownUntil = 0L;
        entry.probeClaimed = false;
        entry.state = State.HEALTHY;
    }

    public synchronized int failureCount(String service, String tier, String routeId) {
        Entry entry = entries.get(new Key(service, tier, routeId));
        return entry == null ? 0 : entry.failures;
    }

    private record Key(String service, String tier, String routeId) { }

    private static final class Entry {
        private int failures;
        private long lastFailureAt;
        private long lastSuccessAt;
        private long cooldownUntil;
        private State state = State.HEALTHY;
        private boolean probeClaimed;
    }
}
