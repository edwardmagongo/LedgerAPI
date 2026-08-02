package com.edwardmagongo.ledgerapi.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs a money operation and records its response against the claim <em>in the same
 * transaction</em>.
 *
 * <p>That single boundary is what makes idempotency sound. Recording the response in a later
 * transaction would leave a window where a crash means money moved with nothing written under the
 * key — and the next retry would move it again, which is the exact bug this feature exists to
 * prevent. Committing both together means money moved if and only if the key knows the outcome.
 *
 * <p>The supplied operation's own {@code @Transactional} method joins this transaction
 * ({@code REQUIRED} propagation), so no separate transaction is created for the money movement.
 */
@Service
public class IdempotentOperationExecutor {

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotentOperationExecutor(IdempotencyKeyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public <T> T executeAndRecord(UUID claimId, int successStatus, Supplier<T> operation) {
        T result = operation.get();
        IdempotencyKey claim = repository.findById(claimId).orElseThrow(
                () -> new IllegalStateException("idempotency claim " + claimId + " disappeared mid-request"));
        claim.complete(successStatus, serialize(result));
        return result;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("could not serialize an idempotent response", ex);
        }
    }
}
