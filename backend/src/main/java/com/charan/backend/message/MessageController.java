package com.charan.backend.message;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for messages.
 *
 * All endpoints are nested under a workspace and channel:
 *   POST /api/workspaces/{workspaceId}/channels/{channelId}/messages
 *   GET  /api/workspaces/{workspaceId}/channels/{channelId}/messages
 *
 * The controller is thin: it extracts the caller id from the JWT,
 * validates the pagination parameters, and delegates to MessageService.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/channels/{channelId}/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * Send a message to a channel.
     * Returns 201 Created with the saved message.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse send(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long workspaceId,
            @PathVariable Long channelId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return messageService.send(userId, workspaceId, channelId, request);
    }

    /**
     * Fetch message history with cursor pagination.
     *
     * Query parameters:
     *   before – message id cursor (optional); returns messages with id < before.
     *   limit  – page size (1–100, default 50).
     *
     * We validate limit here (in the controller) rather than via Bean Validation
     * because it is a query parameter, not a request body field.  ResponseStatusException
     * is the correct way to return 400 from a controller in Spring Boot.
     */
    @GetMapping
    public MessagePageResponse fetchHistory(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long workspaceId,
            @PathVariable Long channelId,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "50") int limit
    ) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and 100");
        }
        Long userId = Long.parseLong(jwt.getSubject());
        return messageService.fetchHistory(userId, workspaceId, channelId, limit, before);
    }
}
