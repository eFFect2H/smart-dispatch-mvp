package com.dispatch.smartdispatch.exception.v1;

import org.springframework.http.HttpStatus;

public class V1ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public V1ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }

    public static V1ApiException badRequest(String message) {
        return new V1ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static V1ApiException conflict(String code, String message) {
        return new V1ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static V1ApiException unprocessable(String code, String message) {
        return new V1ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
