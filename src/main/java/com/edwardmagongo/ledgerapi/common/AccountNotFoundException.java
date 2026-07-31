package com.edwardmagongo.ledgerapi.common;

import org.springframework.http.HttpStatus;
import java.util.UUID;

public class AccountNotFoundException extends ApiException {
    public AccountNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "Account not found: " + id);
    }
}
