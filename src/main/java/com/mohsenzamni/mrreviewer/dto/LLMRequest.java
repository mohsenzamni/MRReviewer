package com.mohsenzamni.mrreviewer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Request payload sent to the LiteLLM / OpenAI-compatible chat completions endpoint.
 */
public record LLMRequest(
        String model,
        List<Message> messages,
        @JsonProperty("response_format")
        ResponseFormat responseFormat
) {

    public record Message(
            String role,
            String content
    ) {}

    public record ResponseFormat(
            String type
    ) {}
}
