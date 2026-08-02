package com.edwardmagongo.ledgerapi.common.idempotency;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The three database writes an idempotent request makes, each in its own committed transaction.
 *
 * <p>Own-transaction is load-bearing on {@link #claim}: a claim is only useful if a
 * <em>concurrent</em> duplicate can see it, and one transaction cannot see another's uncommitted
 * rows. Held inside the caller's transaction, the claim would be invisible, both callers would
 * proceed, and the unique constraint would not fire until commit — by which point the loser has
 * already done the work and holds a rollback-only transaction.
 */
@Service
public class IdempotencyClaims {

    private final IdempotencyClaimInserter inserter;
    private final IdempotencyKeyRepository repository;

    public IdempotencyClaims(IdempotencyClaimInserter inserter, IdempotencyKeyRepository repository) {
        this.inserter = inserter;
        this.repository = repository;
    }

    /**
     * Attempts to claim a key. Returns the new claim's id, or empty if the key is already taken.
     *
     * <p>Deliberately <strong>not</strong> {@code @Transactional}: the INSERT belongs to
     * {@link IdempotencyClaimInserter}'s {@code REQUIRES_NEW} transaction, and this catch has to sit
     * outside that boundary. Inside it, the swallowed violation would surface at commit as an
     * {@code UnexpectedRollbackException} instead — a failed flush marks its transaction
     * rollback-only, and nothing can commit it afterwards.
     */
    public Optional<UUID> claim(UUID userId, String key,
                                IdempotentOperation operation, String fingerprint) {
        try {
            return Optional.of(inserter.insert(userId, key, operation, fingerprint));
        } catch (DataIntegrityViolationException ex) {
            return Optional.empty();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyKey> find(UUID userId, String key) {
        return repository.findByUserIdAndIdempotencyKey(userId, key);
    }

    /** Releases a claim so the key can be used again. Called when the operation failed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID claimId) {
        repository.deleteById(claimId);
    }
}
