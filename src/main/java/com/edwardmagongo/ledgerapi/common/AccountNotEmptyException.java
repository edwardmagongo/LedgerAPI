package com.edwardmagongo.ledgerapi.common;

import org.springframework.http.HttpStatus;
import java.util.UUID;

public class AccountNotEmptyException extends ApiException {
    public AccountNotEmptyException(UUID id) {
        super(HttpStatus.CONFLICT, "Account must have a zero balance before it can be closed: " + id);
    }
}
