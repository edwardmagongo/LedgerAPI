package com.edwardmagongo.ledgerapi.common.idempotency;

import com.edwardmagongo.ledgerapi.account.AccountRepository;
import com.edwardmagongo.ledgerapi.auth.User;
import com.edwardmagongo.ledgerapi.auth.UserRepository;
import com.edwardmagongo.ledgerapi.support.AbstractIntegrationTest;
import com.edwardmagongo.ledgerapi.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyRepositoryTest extends AbstractIntegrationTest {

    @Autowired IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired UserRepository userRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        idempotencyKeyRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(new User("alice@example.com", "hash"));
        bob = userRepository.save(new User("bob@example.com", "hash"));
    }

    @Test
    void storesAndFindsAKeyScopedToItsUser() {
        idempotencyKeyRepository.save(
                new IdempotencyKey("key-1", alice.getId(), IdempotentOperation.TRANSFER, "fp"));

        assertThat(idempotencyKeyRepository.findByUserIdAndIdempotencyKey(alice.getId(), "key-1"))
                .isPresent();
        assertThat(idempotencyKeyRepository.findByUserIdAndIdempotencyKey(bob.getId(), "key-1"))
                .as("keys are namespaced per user")
                .isEmpty();
    }

    @Test
    void theSameKeyStringIsAllowedForTwoDifferentUsers() {
        idempotencyKeyRepository.save(
                new IdempotencyKey("shared", alice.getId(), IdempotentOperation.TRANSFER, "fp"));
        idempotencyKeyRepository.saveAndFlush(
                new IdempotencyKey("shared", bob.getId(), IdempotentOperation.TRANSFER, "fp"));

        assertThat(idempotencyKeyRepository.count()).isEqualTo(2);
    }

    @Test
    void theSameKeyTwiceForOneUserViolatesTheUniqueConstraint() {
        idempotencyKeyRepository.saveAndFlush(
                new IdempotencyKey("dup", alice.getId(), IdempotentOperation.TRANSFER, "fp"));

        assertThatThrownBy(() -> idempotencyKeyRepository.saveAndFlush(
                new IdempotencyKey("dup", alice.getId(), IdempotentOperation.TRANSFER, "fp")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void completingAKeyRecordsTheResponse() {
        IdempotencyKey key = idempotencyKeyRepository.save(
                new IdempotencyKey("key-2", alice.getId(), IdempotentOperation.DEPOSIT, "fp"));
        assertThat(key.isCompleted()).isFalse();

        key.complete(201, "{\"ok\":true}");
        idempotencyKeyRepository.saveAndFlush(key);

        IdempotencyKey reloaded = idempotencyKeyRepository
                .findByUserIdAndIdempotencyKey(alice.getId(), "key-2").orElseThrow();
        assertThat(reloaded.isCompleted()).isTrue();
        assertThat(reloaded.getResponseStatus()).isEqualTo(201);
        assertThat(reloaded.getResponseBody()).isEqualTo("{\"ok\":true}");
        assertThat(reloaded.getCompletedAt()).isNotNull();
    }

    @Test
    void matchesComparesTheFingerprint() {
        IdempotencyKey key = new IdempotencyKey("k", UUID.randomUUID(),
                IdempotentOperation.WITHDRAWAL, "abc123");

        assertThat(key.matches("abc123")).isTrue();
        assertThat(key.matches("different")).isFalse();
    }

    @Test
    void deletingAUserCascadesToTheirKeys() {
        idempotencyKeyRepository.saveAndFlush(
                new IdempotencyKey("key-3", alice.getId(), IdempotentOperation.TRANSFER, "fp"));

        // Existing API tests clear users in setUp; without ON DELETE CASCADE this throws.
        userRepository.deleteAll();

        assertThat(idempotencyKeyRepository.count()).isZero();
    }
}
