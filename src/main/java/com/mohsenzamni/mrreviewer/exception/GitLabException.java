package com.mohsenzamni.mrreviewer.exception;

/**
 * Thrown when a GitLab issue cannot be fetched (e.g. not found or API error).
 */
public class GitLabException extends RuntimeException {

    public GitLabException(String message) {
        super(message);
    }

    public GitLabException(String message, Throwable cause) {
        super(message, cause);
    }
}
