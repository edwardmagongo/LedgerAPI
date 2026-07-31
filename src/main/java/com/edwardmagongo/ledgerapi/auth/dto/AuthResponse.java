package com.edwardmagongo.ledgerapi.auth.dto;

public record AuthResponse(String token, String tokenType, long expiresInSeconds) {
}
