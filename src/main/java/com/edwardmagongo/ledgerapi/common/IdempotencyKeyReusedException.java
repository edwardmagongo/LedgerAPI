package com.edwardmagongo.ledgerapi.common;

import org.springframework.http.HttpStatus;

public class IdempotencyKeyReusedException extends ApiException {
    public IdempotencyKeyReusedException() {
        super(HttpStatus.CONFLICT,
                "This Idempotency-Key was already used with different request parameters");
    }
}
