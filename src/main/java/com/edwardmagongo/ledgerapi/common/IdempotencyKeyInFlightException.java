package com.edwardmagongo.ledgerapi.common;

import org.springframework.http.HttpStatus;

public class IdempotencyKeyInFlightException extends ApiException {
    public IdempotencyKeyInFlightException() {
        super(HttpStatus.CONFLICT,
                "A request with this Idempotency-Key is already in progress; retry shortly");
    }
}
