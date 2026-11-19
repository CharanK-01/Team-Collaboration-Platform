package com.charan.backend.channel;

import jakarta.validation.constraints.NotNull;

public record CreateDmRequest(
        @NotNull(message = "User ID is required")
        Long userId
) {
}
