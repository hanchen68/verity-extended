package com.hanchen.veritycustomapi.resource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class CloudResourceDownloadTest {
    private CloudResourceDownloadTest() {}

    public static void main(String[] args) throws Exception {
        byte[] payload = new byte[256 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31 + 7);

        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "direct-download-test");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/api/v1/download", exchange -> respond(exchange, payload, requests));
        server.start();

        Path root = Files.createTempDirectory("verity-download-test-");
        try {
            String apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/getconfig";
            Path destination = root.resolve("resource.zip");
            Files.write(destination, new byte[]{9, 9, 9});
            new CloudResourceClient(apiUrl, "test-key", 5)
                    .download(null, "download", destination, payload.length * 2L);

            require(Arrays.equals(payload, Files.readAllBytes(destination)), "downloaded bytes differ");
            require(requests.get() == 1, "download did not use a single request");
            require(!Files.exists(destination.resolveSibling("resource.zip.download")), "temporary file was not removed");
            System.out.println("Cloud resource direct overwrite test passed");
        } finally {
            server.stop(0);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void respond(HttpExchange exchange, byte[] payload, AtomicInteger requests)
            throws java.io.IOException {
        requests.incrementAndGet();
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.sendResponseHeaders(200, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
