package com.edwardmagongo.ledgerapi.common;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConflictRetryTest {

    private final ConflictRetry retry = new ConflictRetry();

    @Test
    void returnsResultWithoutRetryingWhenOperationSucceeds() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.execute(() -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void retriesOptimisticLockFailureThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new ObjectOptimisticLockingFailureException("Account", "id");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void retriesDeadlockThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.execute(() -> {
            if (calls.incrementAndGet() < 2) {
                throw new CannotAcquireLockException("deadlock detected");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void throwsWriteConflictWhenAttemptsExhausted() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute(() -> {
            calls.incrementAndGet();
            throw new ObjectOptimisticLockingFailureException("Account", "id");
        })).isInstanceOf(WriteConflictException.class);

        assertThat(calls.get()).isEqualTo(7);
    }

    @Test
    void neverRetriesBusinessExceptions() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute(() -> {
            calls.incrementAndGet();
            throw new InsufficientFundsException();
        })).isInstanceOf(InsufficientFundsException.class);

        assertThat(calls.get()).isEqualTo(1);
    }
}
