package com.edwardmagongo.ledgerapi.common.idempotency;

import com.edwardmagongo.ledgerapi.common.BlankIdempotencyKeyException;
import com.edwardmagongo.ledgerapi.common.ConflictRetry;
import com.edwardmagongo.ledgerapi.common.IdempotencyKeyInFlightException;
import com.edwardmagongo.ledgerapi.common.IdempotencyKeyReusedException;
import com.edwardmagongo.ledgerapi.common.InsufficientFundsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock IdempotencyClaims claims;
    @Mock IdempotentOperationExecutor executor;
    @Mock ConflictRetry conflictRetry;

    @InjectMocks IdempotencyService service;

    private UUID userId;
    private UUID claimId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        claimId = UUID.randomUUID();
    }

    /** Makes ConflictRetry transparent so the supplier runs inline. */
    private void runRetriesInline() {
        when(conflictRetry.execute(any())).thenAnswer(inv -> {
            Supplier<?> supplier = inv.getArgument(0);
            return supplier.get();
        });
    }

    private IdempotencyKey completedKey(String fingerprint, int status, String body) {
        IdempotencyKey key = new IdempotencyKey("k", userId, IdempotentOperation.TRANSFER, fingerprint);
        key.complete(status, body);
        return key;
    }

    @Test
    void aBlankKeyIsRejectedRatherThanTreatedAsAbsent() {
        assertThatThrownBy(() -> service.execute(userId, "   ", IdempotentOperation.TRANSFER, "fp",
                () -> "never runs"))
                .isInstanceOf(BlankIdempotencyKeyException.class);

        verify(claims, never()).claim(any(), any(), any(), any());
    }

    @Test
    void aNullKeyIsRejected() {
        assertThatThrownBy(() -> service.execute(userId, null, IdempotentOperation.TRANSFER, "fp",
                () -> "never runs"))
                .isInstanceOf(BlankIdempotencyKeyException.class);
    }

    @Test
    void aFreshClaimRunsTheOperationAndReturnsItExecuted() {
        when(claims.claim(userId, "k", IdempotentOperation.TRANSFER, "fp"))
                .thenReturn(Optional.of(claimId));
        runRetriesInline();
        when(executor.executeAndRecord(eq(claimId), anyInt(), any())).thenReturn("fresh");

        IdempotentOutcome<String> outcome =
                service.execute(userId, "k", IdempotentOperation.TRANSFER, "fp", () -> "fresh");

        assertThat(outcome).isEqualTo(new IdempotentOutcome.Executed<>("fresh"));
        verify(claims, never()).release(any());
    }

    @Test
    void aTakenKeyWithAMatchingFingerprintReplaysTheStoredResponse() {
        when(claims.claim(userId, "k", IdempotentOperation.TRANSFER, "fp"))
                .thenReturn(Optional.empty());
        when(claims.find(userId, "k"))
                .thenReturn(Optional.of(completedKey("fp", 201, "{\"replayed\":true}")));

        IdempotentOutcome<String> outcome =
                service.execute(userId, "k", IdempotentOperation.TRANSFER, "fp", () -> "must not run");

        assertThat(outcome).isEqualTo(new IdempotentOutcome.Replayed<String>(201, "{\"replayed\":true}"));
        verify(executor, never()).executeAndRecord(any(), anyInt(), any());
    }

    @Test
    void aTakenKeyWithADifferentFingerprintIsRejected() {
        when(claims.claim(userId, "k", IdempotentOperation.TRANSFER, "different"))
                .thenReturn(Optional.empty());
        when(claims.find(userId, "k"))
                .thenReturn(Optional.of(completedKey("original", 201, "{}")));

        assertThatThrownBy(() -> service.execute(userId, "k", IdempotentOperation.TRANSFER,
                "different", () -> "must not run"))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void aTakenKeyStillInProgressIsReportedAsInFlight() {
        when(claims.claim(userId, "k", IdempotentOperation.TRANSFER, "fp"))
                .thenReturn(Optional.empty());
        when(claims.find(userId, "k")).thenReturn(Optional.of(
                new IdempotencyKey("k", userId, IdempotentOperation.TRANSFER, "fp")));

        assertThatThrownBy(() -> service.execute(userId, "k", IdempotentOperation.TRANSFER, "fp",
                () -> "must not run"))
                .isInstanceOf(IdempotencyKeyInFlightException.class);
    }

    @Test
    void aTakenKeyThatHasSinceVanishedIsReportedAsInFlight() {
        when(claims.claim(userId, "k", IdempotentOperation.TRANSFER, "fp"))
                .thenReturn(Optional.empty());
        when(claims.find(userId, "k")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(userId, "k", IdempotentOperation.TRANSFER, "fp",
                () -> "must not run"))
                .isInstanceOf(IdempotencyKeyInFlightException.class);
    }

    @Test
    void aBusinessFailureReleasesTheClaimAndRethrows() {
        when(claims.claim(userId, "k", IdempotentOperation.TRANSFER, "fp"))
                .thenReturn(Optional.of(claimId));
        when(conflictRetry.execute(any())).thenThrow(new InsufficientFundsException());

        assertThatThrownBy(() -> service.execute(userId, "k", IdempotentOperation.TRANSFER, "fp",
                () -> "fails"))
                .isInstanceOf(InsufficientFundsException.class);

        verify(claims).release(claimId);
    }
}
