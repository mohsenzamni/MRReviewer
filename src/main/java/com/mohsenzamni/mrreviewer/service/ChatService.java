package com.mohsenzamni.mrreviewer.service;

import com.mohsenzamni.mrreviewer.client.LiteLLMClient;
import com.mohsenzamni.mrreviewer.dto.ChatRequest;
import com.mohsenzamni.mrreviewer.dto.ChatResponse;
import com.mohsenzamni.mrreviewer.dto.LLMRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages multi-turn AI chat sessions backed by the LiteLLM proxy.
 *
 * <p>Each session is identified by a UUID and holds the ordered message history
 * (system + alternating user / assistant turns).  Sessions are stored in memory
 * and are capped at {@value #MAX_MESSAGES_PER_SESSION} messages to avoid
 * unbounded growth.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Maximum number of user+assistant messages kept per session (system message is not counted). */
    static final int MAX_MESSAGES_PER_SESSION = 100;

    private static final String SYSTEM_PROMPT =
            "You are a helpful AI assistant. Answer questions clearly and concisely. " +
            "When discussing code, use markdown code blocks with the appropriate language tag.";

    private final LiteLLMClient liteLLMClient;

    /**
     * In-memory store: sessionId → mutable message list.
     * The list always starts with the system message at index 0.
     */
    private final Map<String, List<LLMRequest.Message>> sessions = new ConcurrentHashMap<>();

    public ChatService(LiteLLMClient liteLLMClient) {
        this.liteLLMClient = liteLLMClient;
    }

    /**
     * Sends a user message and returns the assistant reply.
     *
     * <p>If {@code request.sessionId()} is {@code null} or blank a new session is
     * created and its identifier is included in the response.
     *
     * @param request the incoming chat request
     * @return the assistant reply together with the (possibly new) session identifier
     */
    public ChatResponse send(ChatRequest request) {
        String sessionId = (request.sessionId() == null || request.sessionId().isBlank())
                ? UUID.randomUUID().toString()
                : request.sessionId();

        List<LLMRequest.Message> history = sessions.computeIfAbsent(sessionId, id -> {
            List<LLMRequest.Message> list = new ArrayList<>();
            list.add(new LLMRequest.Message("system", SYSTEM_PROMPT));
            return list;
        });

        synchronized (history) {
            // Append user message
            history.add(new LLMRequest.Message("user", request.message()));
            log.info("Chat session '{}': sending {} messages to LLM", sessionId, history.size());

            // Call LLM with full conversation context
            String reply = liteLLMClient.chatMultiTurn(List.copyOf(history));

            // Append assistant reply to history
            history.add(new LLMRequest.Message("assistant", reply));

            // Trim oldest non-system messages if cap exceeded (system message at index 0 is never removed)
            while (history.size() > MAX_MESSAGES_PER_SESSION + 1) {
                history.remove(1); // keep system message at index 0
            }

            return new ChatResponse(sessionId, reply);
        }
    }

    /**
     * Removes all history for a session.  The next {@link #send} with the same
     * sessionId will start a fresh conversation.
     *
     * @param sessionId the session to clear
     */
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        log.info("Chat session '{}' cleared", sessionId);
    }
}
