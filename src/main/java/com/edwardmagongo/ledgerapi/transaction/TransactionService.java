package com.edwardmagongo.ledgerapi.transaction;

import com.edwardmagongo.ledgerapi.account.AccountService;
import com.edwardmagongo.ledgerapi.common.ConflictRetry;
import com.edwardmagongo.ledgerapi.transaction.dto.AmountRequest;
import com.edwardmagongo.ledgerapi.transaction.dto.PageResponse;
import com.edwardmagongo.ledgerapi.transaction.dto.TransactionResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Deliberately not {@code @Transactional}: the retry must wrap the transaction boundary, not sit
 * inside it. {@link TransactionExecutor} is a separate bean so the call goes through the Spring
 * proxy and each attempt really does begin a new transaction.
 */
@Service
public class TransactionService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final TransactionExecutor executor;
    private final ConflictRetry conflictRetry;
    private final AccountService accountService;
    private final TransactionRepository transactionRepository;

    public TransactionService(TransactionExecutor executor, ConflictRetry conflictRetry,
                              AccountService accountService, TransactionRepository transactionRepository) {
        this.executor = executor;
        this.conflictRetry = conflictRetry;
        this.accountService = accountService;
        this.transactionRepository = transactionRepository;
    }

    public TransactionResponse deposit(UUID userId, UUID accountId, AmountRequest request) {
        return conflictRetry.execute(() -> executor.deposit(userId, accountId, request));
    }

    public TransactionResponse withdraw(UUID userId, UUID accountId, AmountRequest request) {
        return conflictRetry.execute(() -> executor.withdraw(userId, accountId, request));
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> history(UUID userId, UUID accountId,
                                                     Instant from, Instant to, TransactionType type,
                                                     int page, int size) {
        accountService.requireOwned(userId, accountId);

        int safeSize = Math.clamp(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        // Sorted by id as well as createdAt so that rows written in the same instant keep a
        // stable order across pages instead of shuffling between requests.
        PageRequest pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));

        return PageResponse.from(
                transactionRepository.findHistory(accountId, from, to, type, pageable),
                TransactionResponse::from);
    }
}
