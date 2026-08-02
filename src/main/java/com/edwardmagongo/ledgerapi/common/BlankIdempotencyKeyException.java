package com.edwardmagongo.ledgerapi.common;

import org.springframework.http.HttpStatus;

public class BlankIdempotencyKeyException extends ApiException {
    public BlankIdempotencyKeyException() {
        super(HttpStatus.BAD_REQUEST, "Idempotency-Key must not be blank");
    }
}
