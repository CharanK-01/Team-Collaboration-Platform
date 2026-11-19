package com.charan.backend.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/workspaces/{workspaceId}/channels/{channelId}/messages.
 *
 * "content" is what the API exposes; it maps to the "body" column in the DB.
 *
 * @NotBlank rejects null, empty string, and whitespace-only strings.
 * @Size(max = 4000) caps the message length after trimming.
 * The service trims the value before saving, and re-validates length after trim
 * so a 4001-char string of spaces + one char is still rejected.
 */
public record SendMessageRequest(
        @NotBlank(message = "content must not be blank")
        @Size(max = 4000, message = "content must be at most 4000 characters")
        String content
) {}
