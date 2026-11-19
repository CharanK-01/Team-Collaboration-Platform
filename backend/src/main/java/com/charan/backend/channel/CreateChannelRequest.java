package com.charan.backend.channel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateChannelRequest(
        @NotBlank(message = "Channel name is required")
        @Size(min = 1, max = 80, message = "Channel name must be between 1 and 80 characters")
        @Pattern(regexp = "^[a-z0-9-]+$", message = "Channel name must contain only lowercase letters, digits, and hyphens")
        String name,

        Boolean isPrivate
) {
}
