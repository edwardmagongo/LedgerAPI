package com.edwardmagongo.ledgerapi.transfer;

import com.edwardmagongo.ledgerapi.auth.AuthenticatedUser;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferRequest;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @Valid @RequestBody TransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transferService.transfer(user.id(), request));
    }
}
