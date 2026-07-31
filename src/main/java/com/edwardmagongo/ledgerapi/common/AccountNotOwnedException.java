package com.edwardmagongo.ledgerapi.common;

import org.springframework.http.HttpStatus;

public class AccountNotOwnedException extends ApiException {
    public AccountNotOwnedException() {
        super(HttpStatus.FORBIDDEN, "Account does not belong to the authenticated user");
    }
}
