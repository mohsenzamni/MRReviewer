package com.mohsenzamni.mrreviewer.exception;

/**
 * Thrown when a call to the LLM (LiteLLM) fails.
 */
public class LLMException extends RuntimeException {

    public LLMException(String message) {
        super(message);
    }

    public LLMException(String message, Throwable cause) {
        super(message, cause);
    }
}
