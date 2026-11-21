package com.charan.backend.message;

/**
 * Published inside the send transaction; broadcast happens only after it
 * commits.
 */
public record MessageCreatedEvent(MessageResponse message) {
}