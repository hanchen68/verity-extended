package com.hanchen.veritycustomapi;

import java.net.URI;

public final class NetworkSecurity {
    private NetworkSecurity() {
    }

    public static URI requireSecureUri(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "为空");
        }
        URI uri = URI.create(value.trim());
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new IllegalArgumentException(name + "格式无效");
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return uri;
        }
        if ("http".equalsIgnoreCase(scheme) && isLoopbackHost(host)) {
            return uri;
        }
        throw new IllegalArgumentException(name + "必须使用 HTTPS，HTTP 仅允许回环地址");
    }

    public static String sanitizedEndpoint(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "<invalid>";
            }
            String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
            String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
            return uri.getScheme() + "://" + uri.getHost() + port + path;
        } catch (RuntimeException e) {
            return "<invalid>";
        }
    }

    public static boolean isLoopbackHost(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }
}
