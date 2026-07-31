package com.edwardmagongo.ledgerapi.transfer;

import com.edwardmagongo.ledgerapi.common.ConflictRetry;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferRequest;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferResponse;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Owns the retry loop for transfers.
 *
 * <p>This class is deliberately <strong>not</strong> annotated {@code @Transactional}, and
 * {@link TransferExecutor} is deliberately a <strong>separate bean</strong>. Both details are
 * load-bearing:
 *
 * <ul>
 *   <li>An optimistic-lock failure is raised at flush/commit — as the transactional method exits.
 *       A retry placed inside that method would never observe it, and by the time it did the
 *       transaction would be rollback-only with an unusable persistence context.</li>
 *   <li>Calling {@code this.executeOnce(...)} from the same bean would bypass the Spring proxy
 *       entirely, so {@code @Transactional} would silently not apply.</li>
 * </ul>
 *
 * <p>Going through the proxy from outside the transaction means every retry begins a genuinely
 * new transaction and re-reads current account state.
 */
@Service
public class TransferService {

    private final TransferExecutor executor;
    private final ConflictRetry conflictRetry;

    public TransferService(TransferExecutor executor, ConflictRetry conflictRetry) {
        this.executor = executor;
        this.conflictRetry = conflictRetry;
    }

    public TransferResponse transfer(UUID userId, TransferRequest request) {
        return conflictRetry.execute(() -> executor.executeOnce(userId, request));
    }
}
