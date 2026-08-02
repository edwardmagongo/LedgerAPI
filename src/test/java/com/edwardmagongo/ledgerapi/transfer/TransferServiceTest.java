package com.edwardmagongo.ledgerapi.transfer;

import com.edwardmagongo.ledgerapi.common.ConflictRetry;
import com.edwardmagongo.ledgerapi.common.InsufficientFundsException;
import com.edwardmagongo.ledgerapi.common.WriteConflictException;
import com.edwardmagongo.ledgerapi.common.idempotency.IdempotencyService;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferRequest;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock TransferExecutor executor;
    @Mock IdempotencyService idempotencyService;

    // The real ConflictRetry is used, not a mock: the retry behaviour is exactly what is under test.
    private final ConflictRetry conflictRetry = new ConflictRetry();

    private final UUID userId = UUID.randomUUID();
    private final TransferRequest request =
            new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"));

    private TransferService service() {
        return new TransferService(executor, conflictRetry, idempotencyService);
    }

    private TransferResponse anyResponse() {
        return new TransferResponse(UUID.randomUUID(), request.fromAccountId(), request.toAccountId(),
                request.amount(), new BigDecimal("90.00"), new BigDecimal("10.00"), Instant.now());
    }

    @Test
    void callsExecutorExactlyOnceWhenThereIsNoConflict() {
        TransferResponse expected = anyResponse();
        when(executor.executeOnce(userId, request)).thenReturn(expected);

        assertThat(service().transfer(userId, request)).isSameAs(expected);

        verify(executor, times(1)).executeOnce(eq(userId), any());
    }

    @Test
    void retriesOptimisticLockFailureAndSucceedsOnTheSecondAttempt() {
        TransferResponse expected = anyResponse();
        when(executor.executeOnce(userId, request))
                .thenThrow(new ObjectOptimisticLockingFailureException("Account", "id"))
                .thenReturn(expected);

        assertThat(service().transfer(userId, request)).isSameAs(expected);

        verify(executor, times(2)).executeOnce(eq(userId), any());
    }

    @Test
    void retriesDeadlockAndSucceedsOnTheThirdAttempt() {
        TransferResponse expected = anyResponse();
        when(executor.executeOnce(userId, request))
                .thenThrow(new CannotAcquireLockException("deadlock detected"))
                .thenThrow(new CannotAcquireLockException("deadlock detected"))
                .thenReturn(expected);

        assertThat(service().transfer(userId, request)).isSameAs(expected);

        verify(executor, times(3)).executeOnce(eq(userId), any());
    }

    @Test
    void surfaces409AfterSevenFailedAttempts() {
        when(executor.executeOnce(userId, request))
                .thenThrow(new ObjectOptimisticLockingFailureException("Account", "id"));

        assertThatThrownBy(() -> service().transfer(userId, request))
                .isInstanceOf(WriteConflictException.class);

        verify(executor, times(7)).executeOnce(eq(userId), any());
    }

    @Test
    void doesNotRetryInsufficientFunds() {
        when(executor.executeOnce(userId, request)).thenThrow(new InsufficientFundsException());

        assertThatThrownBy(() -> service().transfer(userId, request))
                .isInstanceOf(InsufficientFundsException.class);

        verify(executor, times(1)).executeOnce(eq(userId), any());
    }
}
