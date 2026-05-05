package com.mohsenzamni.mrreviewer.controller;

import com.mohsenzamni.mrreviewer.dto.ChatRequest;
import com.mohsenzamni.mrreviewer.dto.ChatResponse;
import com.mohsenzamni.mrreviewer.service.ChatService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry point for the multi-turn AI chat feature.
 *
 * <pre>
 * POST /chat
 * { "sessionId": "optional-uuid", "message": "Hello!" }
 * → { "sessionId": "uuid", "reply": "Hi there! …" }
 *
 * DELETE /chat/{sessionId}   — clears conversation history for that session
 * </pre>
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("Received chat message (sessionId={})", request.sessionId());
        ChatResponse response = chatService.send(request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        log.info("Clearing chat session: {}", sessionId);
        chatService.clearSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}
