package com.agrisense.backend.exception;

/*
 * The resource exists but belongs to another farmer (403).
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
