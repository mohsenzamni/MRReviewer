package com.mohsenzamni.mrreviewer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for a single chat turn.
 *
 * <p>{@code sessionId} is optional on the first message; if absent the server
 * starts a new conversation and returns a fresh session identifier.
 * Pass the returned {@code sessionId} in every subsequent request to continue
 * the same conversation.
 */
public record ChatRequest(
        /** Existing session identifier, or {@code null} / omitted to start a new session. */
        String sessionId,

        /** The user's message text. */
        @NotBlank(message = "message must not be blank")
        @Size(max = 32_000, message = "message must be at most 32 000 characters")
        String message
) {}
