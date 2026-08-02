package com.edwardmagongo.ledgerapi.common.idempotency;

import com.edwardmagongo.ledgerapi.auth.User;
import com.edwardmagongo.ledgerapi.auth.UserRepository;
import com.edwardmagongo.ledgerapi.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyClaimsTest extends AbstractIntegrationTest {

    @Autowired IdempotencyClaims claims;
    @Autowired IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired UserRepository userRepository;

    private UUID aliceId;

    @BeforeEach
    void setUp() {
        idempotencyKeyRepository.deleteAll();
        userRepository.deleteAll();
        aliceId = userRepository.save(new User("alice@example.com", "hash")).getId();
    }

    @Test
    void claimingAFreshKeySucceedsAndIsImmediatelyVisible() {
        Optional<UUID> claimId = claims.claim(aliceId, "k1", IdempotentOperation.TRANSFER, "fp");

        assertThat(claimId).isPresent();
        // Must be committed, not merely pending: a concurrent duplicate can only see a
        // committed claim.
        assertThat(claims.find(aliceId, "k1")).isPresent();
    }

    @Test
    void claimingATakenKeyReturnsEmptyRatherThanThrowing() {
        assertThat(claims.claim(aliceId, "dup", IdempotentOperation.TRANSFER, "fp")).isPresent();

        assertThat(claims.claim(aliceId, "dup", IdempotentOperation.TRANSFER, "fp"))
                .as("the unique constraint must be reported as an empty Optional")
                .isEmpty();
    }

    @Test
    void aFreshClaimStartsInProgressWithNoRecordedResponse() {
        claims.claim(aliceId, "k2", IdempotentOperation.DEPOSIT, "fp");

        IdempotencyKey stored = claims.find(aliceId, "k2").orElseThrow();
        assertThat(stored.isCompleted()).isFalse();
        assertThat(stored.getResponseBody()).isNull();
        assertThat(stored.getResponseStatus()).isNull();
    }

    @Test
    void releasingAClaimMakesTheKeyReusable() {
        UUID claimId = claims.claim(aliceId, "k3", IdempotentOperation.WITHDRAWAL, "fp").orElseThrow();

        claims.release(claimId);

        assertThat(claims.find(aliceId, "k3")).isEmpty();
        assertThat(claims.claim(aliceId, "k3", IdempotentOperation.WITHDRAWAL, "fp")).isPresent();
    }

    @Test
    void findIsScopedToTheUser() {
        claims.claim(aliceId, "shared", IdempotentOperation.TRANSFER, "fp");

        assertThat(claims.find(UUID.randomUUID(), "shared")).isEmpty();
    }
}
