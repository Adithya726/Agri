package com.agrisense.backend.exception;

/*
 * The requested farmer, farm, farm state or plan does not exist (404).
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
