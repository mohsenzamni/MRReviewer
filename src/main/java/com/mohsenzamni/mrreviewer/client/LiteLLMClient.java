package com.mohsenzamni.mrreviewer.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mohsenzamni.mrreviewer.config.AppConfig;
import com.mohsenzamni.mrreviewer.dto.LLMRequest;
import com.mohsenzamni.mrreviewer.dto.LLMResponse;
import com.mohsenzamni.mrreviewer.exception.LLMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;

/**
 * Sends chat-completion requests to a LiteLLM instance that exposes an
 * OpenAI-compatible {@code /chat/completions} endpoint.
 *
 * <p>The API key is sent in the {@code Authorization} header and is never logged.
 */
@Component
public class LiteLLMClient {

    private static final Logger log = LoggerFactory.getLogger(LiteLLMClient.class);

    private final RestTemplate restTemplate;
    private final AppConfig config;
    private final ObjectMapper objectMapper;

    public LiteLLMClient(RestTemplate restTemplate, AppConfig config, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * Sends {@code systemPrompt} and {@code userPrompt} to the LLM and returns the raw text
     * content of the first choice.
     *
     * @param systemPrompt instructions for the model
     * @param userPrompt   the user-facing input (issue + diff)
     * @return the model's reply text
     * @throws LLMException on any communication or parsing failure
     */
    public String chat(String systemPrompt, String userPrompt) {
        AppConfig.LiteLLM llmConfig = config.getLitellm();

        LLMRequest requestBody = new LLMRequest(
                llmConfig.getModelName(),
                List.of(
                        new LLMRequest.Message("system", systemPrompt),
                        new LLMRequest.Message("user", userPrompt)
                ),
                new LLMRequest.ResponseFormat("json_object")
        );

        String endpoint = llmConfig.getBaseUrl() + "/chat/completions";
        log.info("Calling LiteLLM at {} with model '{}'", endpoint, llmConfig.getModelName());

        try {
            HttpHeaders headers = buildHeaders();
            HttpEntity<LLMRequest> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<LLMResponse> response =
                    restTemplate.exchange(URI.create(endpoint), HttpMethod.POST, entity,
                            LLMResponse.class);

            LLMResponse body = response.getBody();
            if (body == null || body.choices() == null || body.choices().isEmpty()) {
                throw new LLMException("Empty or invalid response from LiteLLM");
            }

            String content = body.choices().get(0).message().content();
            if (content == null || content.isBlank()) {
                throw new LLMException("LiteLLM returned an empty message content");
            }
            return content;

        } catch (RestClientException ex) {
            throw new LLMException("LiteLLM request failed: " + ex.getMessage(), ex);
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String apiKey = config.getLitellm().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            // API key is intentionally NOT logged
            headers.setBearerAuth(apiKey);
        }
        return headers;
    }
}
