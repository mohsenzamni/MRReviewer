package com.mohsenzamni.mrreviewer.dto;

import java.util.List;

/**
 * Response from the LiteLLM / OpenAI-compatible chat completions endpoint.
 */
public record LLMResponse(
        List<Choice> choices
) {

    public record Choice(
            Message message
    ) {}

    public record Message(
            String role,
            String content
    ) {}
}
