package com.edwardmagongo.ledgerapi.common;

import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends ApiException {
    public InsufficientFundsException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds");
    }
}
