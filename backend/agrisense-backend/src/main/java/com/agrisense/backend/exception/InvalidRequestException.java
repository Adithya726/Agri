package com.agrisense.backend.exception;

/*
 * The request body is missing required values (400).
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
