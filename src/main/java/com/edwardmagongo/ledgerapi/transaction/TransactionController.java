package com.edwardmagongo.ledgerapi.transaction;

import com.edwardmagongo.ledgerapi.auth.AuthenticatedUser;
import com.edwardmagongo.ledgerapi.transaction.dto.AmountRequest;
import com.edwardmagongo.ledgerapi.transaction.dto.PageResponse;
import com.edwardmagongo.ledgerapi.transaction.dto.TransactionResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts/{accountId}")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/deposit")
    public ResponseEntity<TransactionResponse> deposit(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @PathVariable UUID accountId,
                                                       @Valid @RequestBody AmountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.deposit(user.id(), accountId, request));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @PathVariable UUID accountId,
                                                        @Valid @RequestBody AmountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.withdraw(user.id(), accountId, request));
    }

    @GetMapping("/transactions")
    public PageResponse<TransactionResponse> history(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return transactionService.history(user.id(), accountId, from, to, type, page, size);
    }
}
