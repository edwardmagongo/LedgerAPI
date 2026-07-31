package com.edwardmagongo.ledgerapi.common;

import org.springframework.http.HttpStatus;

public class SelfTransferException extends ApiException {
    public SelfTransferException() {
        super(HttpStatus.BAD_REQUEST, "Source and destination accounts must be different");
    }
}
