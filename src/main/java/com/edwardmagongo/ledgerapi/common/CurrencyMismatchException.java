package com.edwardmagongo.ledgerapi.common;

import org.springframework.http.HttpStatus;

public class CurrencyMismatchException extends ApiException {
    public CurrencyMismatchException() {
        super(HttpStatus.BAD_REQUEST, "Source and destination accounts must use the same currency");
    }
}
