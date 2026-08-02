package com.edwardmagongo.ledgerapi.common.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Turns an {@link IdempotentOutcome} into an HTTP response. */
public final class IdempotentResponses {

    private IdempotentResponses() {
    }

    public static <T> ResponseEntity<?> toResponseEntity(IdempotentOutcome<T> outcome) {
        return switch (outcome) {
            case IdempotentOutcome.Executed<T> executed ->
                    ResponseEntity.status(HttpStatus.CREATED).body(executed.body());
            // The body is already a JSON string, so the content type must be set explicitly —
            // otherwise Spring would return it as text/plain.
            case IdempotentOutcome.Replayed<T> replayed ->
                    ResponseEntity.status(replayed.httpStatus())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(replayed.rawJson());
        };
    }
}
