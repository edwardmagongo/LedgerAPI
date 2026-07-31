package com.edwardmagongo.ledgerapi.transaction;

import com.edwardmagongo.ledgerapi.common.ConflictRetry;
import com.edwardmagongo.ledgerapi.transaction.dto.AmountRequest;
import com.edwardmagongo.ledgerapi.transaction.dto.TransactionResponse;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Deliberately not {@code @Transactional}: the retry must wrap the transaction boundary, not sit
 * inside it. {@link TransactionExecutor} is a separate bean so the call goes through the Spring
 * proxy and each attempt really does begin a new transaction.
 */
@Service
public class TransactionService {

    private final TransactionExecutor executor;
    private final ConflictRetry conflictRetry;

    public TransactionService(TransactionExecutor executor, ConflictRetry conflictRetry) {
        this.executor = executor;
        this.conflictRetry = conflictRetry;
    }

    public TransactionResponse deposit(UUID userId, UUID accountId, AmountRequest request) {
        return conflictRetry.execute(() -> executor.deposit(userId, accountId, request));
    }

    public TransactionResponse withdraw(UUID userId, UUID accountId, AmountRequest request) {
        return conflictRetry.execute(() -> executor.withdraw(userId, accountId, request));
    }
}
