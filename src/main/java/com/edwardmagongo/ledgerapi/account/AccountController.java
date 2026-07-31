package com.edwardmagongo.ledgerapi.account;

import com.edwardmagongo.ledgerapi.account.dto.AccountResponse;
import com.edwardmagongo.ledgerapi.account.dto.CreateAccountRequest;
import com.edwardmagongo.ledgerapi.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.create(user.id(), request));
    }

    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return accountService.list(user.id());
    }

    @GetMapping("/{accountId}")
    public AccountResponse get(@AuthenticationPrincipal AuthenticatedUser user,
                               @PathVariable UUID accountId) {
        return accountService.get(user.id(), accountId);
    }

    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@AuthenticationPrincipal AuthenticatedUser user,
                      @PathVariable UUID accountId) {
        accountService.close(user.id(), accountId);
    }
}
