package com.edwardmagongo.ledgerapi.common;

import org.springframework.http.HttpStatus;

public class WriteConflictException extends ApiException {
    public WriteConflictException() {
        super(HttpStatus.CONFLICT, "The account was modified concurrently; please retry the request");
    }
}
