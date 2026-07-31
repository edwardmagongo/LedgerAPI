package com.edwardmagongo.ledgerapi.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Runs an operation, retrying only write-conflict failures.
 *
 * <p>This must always be invoked from <em>outside</em> a transaction, wrapping a call to a
 * separate {@code @Transactional} bean. An optimistic-lock failure surfaces at flush/commit —
 * that is, as the transactional method exits — so a retry inside that method would never see it,
 * and the transaction would already be rollback-only. Each retry here starts a genuinely fresh
 * transaction with a fresh persistence context and freshly read entity state.
 */
@Component
public class ConflictRetry {

    private static final Logger log = LoggerFactory.getLogger(ConflictRetry.class);
    private static final int MAX_ATTEMPTS = 7;
    private static final long BASE_BACKOFF_MILLIS = 25;
    private static final long MAX_BACKOFF_MILLIS = 400;

    public <T> T execute(Supplier<T> operation) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return operation.get();
            } catch (ObjectOptimisticLockingFailureException | CannotAcquireLockException ex) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("Write conflict persisted after {} attempts", MAX_ATTEMPTS);
                    throw new WriteConflictException();
                }
                log.debug("Write conflict on attempt {}, retrying", attempt);
                backoff(attempt);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    // Full jitter: sleep uniformly at random within [0, cap), where cap grows exponentially per
    // attempt (capped at MAX_BACKOFF_MILLIS), so competing threads decorrelate far more effectively
    // than a narrow fixed-width jitter band added on top of a fixed per-attempt floor.
    private void backoff(int attempt) {
        long cap = Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS * (1L << attempt));
        long sleepMillis = ThreadLocalRandom.current().nextLong(cap);
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new WriteConflictException();
        }
    }
}
