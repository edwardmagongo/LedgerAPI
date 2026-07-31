package com.edwardmagongo.ledgerapi.auth;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hs256-0123456789";

    private final JwtService jwtService = new JwtService(SECRET, 60);

    @Test
    void generatedTokenRoundTripsToTheSameUser() {
        User user = new User("alice@example.com", "hash");

        AuthenticatedUser parsed = jwtService.parse(jwtService.generateToken(user));

        assertThat(parsed.id()).isEqualTo(user.getId());
        assertThat(parsed.email()).isEqualTo("alice@example.com");
    }

    @Test
    void rejectsTokenSignedWithADifferentSecret() {
        JwtService attacker = new JwtService("a-completely-different-secret-key-also-long-enough-12345", 60);
        String forged = attacker.generateToken(new User("mallory@example.com", "hash"));

        assertThatThrownBy(() -> jwtService.parse(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() {
        JwtService expiring = new JwtService(SECRET, -1);
        String expired = expiring.generateToken(new User("alice@example.com", "hash"));

        assertThatThrownBy(() -> jwtService.parse(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsGarbageToken() {
        assertThatThrownBy(() -> jwtService.parse("not.a.jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSubjectIsTheUserId() {
        User user = new User("alice@example.com", "hash");

        assertThat(UUID.fromString(jwtService.parse(jwtService.generateToken(user)).id().toString()))
                .isEqualTo(user.getId());
    }
}
