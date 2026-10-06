package com.fd.jfetch.jsearch;

public class JSearchException extends RuntimeException {
    public JSearchException(String message) {
        super(message);
    }

    public JSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
