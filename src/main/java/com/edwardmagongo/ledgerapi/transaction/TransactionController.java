package com.edwardmagongo.ledgerapi.transaction;

import com.edwardmagongo.ledgerapi.auth.AuthenticatedUser;
import com.edwardmagongo.ledgerapi.common.idempotency.IdempotentResponses;
import com.edwardmagongo.ledgerapi.transaction.dto.AmountRequest;
import com.edwardmagongo.ledgerapi.transaction.dto.PageResponse;
import com.edwardmagongo.ledgerapi.transaction.dto.TransactionResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    @ApiResponse(responseCode = "201", description = "Deposit recorded",
            content = @Content(schema = @Schema(implementation = TransactionResponse.class)))
    @PostMapping("/deposit")
    public ResponseEntity<?> deposit(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID accountId,
            @Parameter(description = "Optional. Repeat the same key to safely retry a deposit "
                    + "whose response was never received.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AmountRequest request) {

        if (idempotencyKey == null) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(transactionService.deposit(user.id(), accountId, request));
        }
        return IdempotentResponses.toResponseEntity(
                transactionService.deposit(user.id(), idempotencyKey, accountId, request));
    }

    @ApiResponse(responseCode = "201", description = "Withdrawal recorded",
            content = @Content(schema = @Schema(implementation = TransactionResponse.class)))
    @PostMapping("/withdraw")
    public ResponseEntity<?> withdraw(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID accountId,
            @Parameter(description = "Optional. Repeat the same key to safely retry a withdrawal "
                    + "whose response was never received.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AmountRequest request) {

        if (idempotencyKey == null) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(transactionService.withdraw(user.id(), accountId, request));
        }
        return IdempotentResponses.toResponseEntity(
                transactionService.withdraw(user.id(), idempotencyKey, accountId, request));
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
