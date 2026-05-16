package io.github.the_infinite.framework.controller;

import io.github.the_infinite.framework.response.ServiceResult;

@SuppressWarnings("unused")
public final class Okay<T> extends ServiceResult<T> {
    public Okay(String status, String message, T data, int code) {
        super(status, message, data, code);
    }

    public Okay(String status, String message, T data) {
        this(status, message, data, 200);
    }

    public Okay(String message, T data) {
        this("OK", message, data);
    }

    public Okay(T data) {
        this("OK", "Okay", data);
    }
}
