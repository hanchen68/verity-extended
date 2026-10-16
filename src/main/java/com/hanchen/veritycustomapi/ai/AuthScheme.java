package com.hanchen.veritycustomapi.ai;

public enum AuthScheme {
    BEARER("bearer"),
    API_KEY_HEADER("api_key_header"),
    API_KEY_QUERY("api_key_query");

    private final String value;

    AuthScheme(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

