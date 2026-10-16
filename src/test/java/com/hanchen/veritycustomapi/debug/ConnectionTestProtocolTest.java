package com.hanchen.veritycustomapi.debug;

import java.net.URI;

public final class ConnectionTestProtocolTest {
    private ConnectionTestProtocolTest() {
    }

    public static void main(String[] args) {
        assertReachable(200, true);
        assertReachable(204, true);
        assertReachable(400, true);
        assertReachable(405, true);
        assertReachable(415, true);
        assertReachable(422, true);
        assertReachable(401, false);
        assertReachable(404, false);
        assertReachable(429, false);
        assertReachable(500, false);

        URI source = URI.create("https://example.com/v1/audio/speech?api-version=2026-01-01");
        URI rebuilt = ConnectionTestHelper.rebuildUri(source, "/v1/models", source.getRawQuery());
        assertEquals("https://example.com/v1/models?api-version=2026-01-01", rebuilt.toString());

        URI appended = ConnectionTestHelper.appendQuery(source, "api_key", "a%2Bb");
        assertEquals("https://example.com/v1/audio/speech?api-version=2026-01-01&api_key=a%2Bb", appended.toString());

        URI noQuery = ConnectionTestHelper.appendQuery(
                URI.create("http://127.0.0.1:8080/v1/models"), "api_key", "test"
        );
        assertEquals("http://127.0.0.1:8080/v1/models?api_key=test", noQuery.toString());

        System.out.println("Connection test protocol checks passed");
    }

    private static void assertReachable(int status, boolean expected) {
        boolean actual = ConnectionTestHelper.isReachableProbeStatus(status);
        if (actual != expected) {
            throw new AssertionError("HTTP " + status + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }
}
