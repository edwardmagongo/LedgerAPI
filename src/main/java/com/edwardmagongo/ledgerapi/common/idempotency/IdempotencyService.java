package com.edwardmagongo.ledgerapi.common.idempotency;

import com.edwardmagongo.ledgerapi.common.BlankIdempotencyKeyException;
import com.edwardmagongo.ledgerapi.common.ConflictRetry;
import com.edwardmagongo.ledgerapi.common.IdempotencyKeyInFlightException;
import com.edwardmagongo.ledgerapi.common.IdempotencyKeyReusedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Orchestrates an idempotent request: claim, run, record — or replay a previous result.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}, and {@link IdempotencyClaims} and
 * {@link IdempotentOperationExecutor} are deliberately separate beans. This mirrors the split
 * between {@code TransferService} and {@code TransferExecutor}: calling a {@code @Transactional}
 * method on {@code this} bypasses the Spring proxy, so the annotation would silently do nothing,
 * and the claim would never get its own committed transaction.
 */
@Service
public class IdempotencyService {

    // All three covered endpoints are 201-returning creates. If one ever is not, make this a
    // parameter rather than guessing here.
    private static final int SUCCESS_STATUS = HttpStatus.CREATED.value();

    private final IdempotencyClaims claims;
    private final IdempotentOperationExecutor executor;
    private final ConflictRetry conflictRetry;

    public IdempotencyService(IdempotencyClaims claims, IdempotentOperationExecutor executor,
                              ConflictRetry conflictRetry) {
        this.claims = claims;
        this.executor = executor;
        this.conflictRetry = conflictRetry;
    }

    public <T> IdempotentOutcome<T> execute(UUID userId, String key, IdempotentOperation operation,
                                            String fingerprint, Supplier<T> body) {
        // Treating a blank key as "absent" would leave the caller believing they were protected.
        if (key == null || key.isBlank()) {
            throw new BlankIdempotencyKeyException();
        }

        Optional<UUID> claimId = claims.claim(userId, key, operation, fingerprint);
        if (claimId.isEmpty()) {
            return replay(userId, key, fingerprint);
        }

        UUID id = claimId.get();
        try {
            return new IdempotentOutcome.Executed<>(
                    conflictRetry.execute(() -> executor.executeAndRecord(id, SUCCESS_STATUS, body)));
        } catch (RuntimeException ex) {
            // The operation rolled back, so no money moved: release the key rather than leaving a
            // claim that would block every future retry.
            claims.release(id);
            throw ex;
        }
    }

    private <T> IdempotentOutcome<T> replay(UUID userId, String key, String fingerprint) {
        IdempotencyKey existing = claims.find(userId, key)
                // Lost the insert race, then the winner released its claim after failing. Nothing
                // to replay and nothing owned, so report it as in flight and let the caller retry.
                .orElseThrow(IdempotencyKeyInFlightException::new);

        if (!existing.matches(fingerprint)) {
            throw new IdempotencyKeyReusedException();
        }
        if (!existing.isCompleted()) {
            throw new IdempotencyKeyInFlightException();
        }
        return new IdempotentOutcome.Replayed<>(existing.getResponseStatus(), existing.getResponseBody());
    }
}
