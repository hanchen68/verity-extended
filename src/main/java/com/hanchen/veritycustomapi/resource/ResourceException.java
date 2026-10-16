package com.hanchen.veritycustomapi.resource;

public final class ResourceException extends Exception {
    private final int status;

    public ResourceException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ResourceException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public int status() {
        return status;
    }
}
