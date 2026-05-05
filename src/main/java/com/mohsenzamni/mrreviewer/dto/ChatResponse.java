package com.mohsenzamni.mrreviewer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response returned by {@code POST /chat}.
 */
public record ChatResponse(
        /** Session identifier — pass this back on the next request to continue the conversation. */
        @JsonProperty("sessionId")
        String sessionId,

        /** The assistant's reply text. */
        @JsonProperty("reply")
        String reply
) {}
