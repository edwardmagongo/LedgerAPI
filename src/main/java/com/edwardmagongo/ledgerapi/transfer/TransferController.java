package com.edwardmagongo.ledgerapi.transfer;

import com.edwardmagongo.ledgerapi.auth.AuthenticatedUser;
import com.edwardmagongo.ledgerapi.common.idempotency.IdempotentResponses;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferRequest;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    // The declared response schema is restated because the return type is ResponseEntity<?>: a
    // replay returns a pre-serialized JSON string, so the handler cannot be typed to the DTO.
    @ApiResponse(responseCode = "201", description = "Transfer completed",
            content = @Content(schema = @Schema(implementation = TransferResponse.class)))
    @PostMapping
    public ResponseEntity<?> transfer(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "Optional. Repeat the same key to safely retry a transfer "
                    + "whose response was never received.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        if (idempotencyKey == null) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(transferService.transfer(user.id(), request));
        }
        return IdempotentResponses.toResponseEntity(
                transferService.transfer(user.id(), idempotencyKey, request));
    }
}
