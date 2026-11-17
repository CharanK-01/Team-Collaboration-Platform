package com.charan.backend.auth;

public record AuthResponse(
        String token,
        String tokenType,
        long expiresInSeconds,
        UserResponse user) {
}