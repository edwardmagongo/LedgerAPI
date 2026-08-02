package com.edwardmagongo.ledgerapi.common.idempotency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Inserts a claim row in a transaction of its own, and lets a unique-constraint violation escape.
 *
 * <p>A separate bean from {@link IdempotencyClaims} for the same reason {@code TransferExecutor} is
 * separate from {@code TransferService}: the caller needs to <em>catch</em> the violation, and a
 * failed flush marks its transaction rollback-only. Caught inside the transaction that raised it,
 * the swallowed exception would be replaced at commit by an {@code UnexpectedRollbackException} —
 * so the catch has to sit outside this transaction boundary, which means outside this method, which
 * means outside this bean. Calling it on {@code this} would bypass the proxy and lose the boundary
 * entirely.
 *
 * <p>{@code saveAndFlush}, not {@code save}: the entity assigns its own {@code @Id} and carries no
 * {@code @Version}, so Spring Data treats it as detached and routes through {@code em.merge()},
 * which defers the INSERT to commit — past the point where the caller can attribute it to this
 * claim. This is the same trap documented in {@code AuthService#register}.
 */
@Service
public class IdempotencyClaimInserter {

    private final IdempotencyKeyRepository repository;

    public IdempotencyClaimInserter(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException if the key is already claimed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID insert(UUID userId, String key,
                       IdempotentOperation operation, String fingerprint) {
        return repository.saveAndFlush(
                new IdempotencyKey(key, userId, operation, fingerprint)).getId();
    }
}
