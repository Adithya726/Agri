package com.agrisense.backend.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/*
 * The request body is missing required values or holds invalid ones (400).
 * Optionally carries per-field messages so a form can show them inline.
 */
public class InvalidRequestException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public InvalidRequestException(String message) {
        super(message);
        this.fieldErrors = Collections.emptyMap();
    }

    public InvalidRequestException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
