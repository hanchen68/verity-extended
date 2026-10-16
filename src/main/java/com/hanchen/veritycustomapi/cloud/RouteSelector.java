package com.hanchen.veritycustomapi.cloud;

import java.util.ArrayList;
import java.util.List;

public final class RouteSelector {
    public List<RouteDto> select(String service, String configuredTier, String routeId,
                                 List<? extends RouteDto> routes, RoutePolicy policy,
                                 RouteHealthRegistry health, long now) {
        String tier = normalizeTier(configuredTier);
        RoutePolicy effectivePolicy = policy == null ? RoutePolicy.defaults() : policy;
        List<RouteDto> eligible = new ArrayList<>();
        if (routes != null) {
            for (RouteDto route : routes) {
                if (route != null && tier.equals(normalizeTier(route.tier)) && Boolean.TRUE.equals(route.enabled)
                        && route.routeId != null && !route.routeId.isBlank()) {
                    eligible.add(route);
                }
            }
        }

        eligible.sort((left, right) -> {
            int leftPriority = left.priority != null ? left.priority : 0;
            int rightPriority = right.priority != null ? right.priority : 0;
            int compared = Integer.compare(leftPriority, rightPriority);
            return effectivePolicy.largeFirst() ? -compared : compared;
        });

        List<RouteDto> ordered = new ArrayList<>();
        boolean specified = routeId != null && !routeId.isBlank() && !"auto".equalsIgnoreCase(routeId);
        if (specified) {
            for (RouteDto route : eligible) {
                if (routeId.equals(route.routeId)) {
                    ordered.add(route);
                    break;
                }
            }
            if (ordered.isEmpty()) return ordered;
        }
        if (!specified || effectivePolicy.autoFailover) {
            for (RouteDto route : eligible) {
                if (!ordered.contains(route)) ordered.add(route);
            }
        }
        if (health == null) return ordered;
        List<RouteDto> available = new ArrayList<>();
        for (RouteDto route : ordered) {
            if (health.isSelectable(service, tier, route.routeId, now, effectivePolicy)) available.add(route);
        }
        return available;
    }

    public RouteDto selectFirst(String service, String configuredTier, String routeId,
                                List<? extends RouteDto> routes, RoutePolicy policy,
                                RouteHealthRegistry health, long now) {
        List<RouteDto> selected = select(service, configuredTier, routeId, routes, policy, health, now);
        return selected.isEmpty() ? null : selected.get(0);
    }

    private static String normalizeTier(String tier) {
        return "paid".equalsIgnoreCase(tier) ? "paid" : "free";
    }
}
