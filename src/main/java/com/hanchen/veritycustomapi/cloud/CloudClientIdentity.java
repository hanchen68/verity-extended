package com.hanchen.veritycustomapi.cloud;

public final class CloudClientIdentity {
    private CloudClientIdentity() {
    }

    public static String get() {
        return CloudCredentialStore.getInstance().clientId();
    }
}
