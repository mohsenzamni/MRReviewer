package com.mohsenzamni.mrreviewer.exception;

/**
 * Thrown when executing a git command fails (e.g. no repo, no remote).
 */
public class GitException extends RuntimeException {

    public GitException(String message) {
        super(message);
    }

    public GitException(String message, Throwable cause) {
        super(message, cause);
    }
}
