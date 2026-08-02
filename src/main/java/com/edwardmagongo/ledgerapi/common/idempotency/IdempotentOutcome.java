package com.edwardmagongo.ledgerapi.common.idempotency;

/**
 * The two shapes an idempotent request can return. A fresh call produces a typed DTO; a replay
 * produces a stored status code and an already-serialized JSON string. Keeping them as distinct
 * variants avoids forcing one shape onto the other and keeps serialization at the HTTP edge.
 */
public sealed interface IdempotentOutcome<T> {

    record Executed<T>(T body) implements IdempotentOutcome<T> {
    }

    record Replayed<T>(int httpStatus, String rawJson) implements IdempotentOutcome<T> {
    }
}
