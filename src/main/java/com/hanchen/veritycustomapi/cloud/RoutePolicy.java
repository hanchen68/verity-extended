package com.hanchen.veritycustomapi.cloud;

public class RoutePolicy {
    public boolean autoFailover = true;
    public int failoverAfterErrors = 10;
    public int healthCheckIntervalSeconds = 3600;
    public String priorityDirection = "small_first";

    public static RoutePolicy defaults() {
        return new RoutePolicy();
    }

    public int threshold() {
        return Math.max(1, failoverAfterErrors);
    }

    public long healthIntervalMillis() {
        return Math.max(1, healthCheckIntervalSeconds) * 1000L;
    }

    public boolean largeFirst() {
        return "large_first".equalsIgnoreCase(priorityDirection);
    }
}
