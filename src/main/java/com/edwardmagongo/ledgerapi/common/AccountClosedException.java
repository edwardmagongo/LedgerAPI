package com.edwardmagongo.ledgerapi.common;

import org.springframework.http.HttpStatus;
import java.util.UUID;

public class AccountClosedException extends ApiException {
    public AccountClosedException(UUID id) {
        super(HttpStatus.CONFLICT, "Account is closed: " + id);
    }
}
