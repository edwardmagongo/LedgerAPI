package com.edwardmagongo.ledgerapi.common;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final HttpServletRequest request = new MockHttpServletRequest("POST", "/api/transfers");

    @Test
    void mapsInsufficientFundsTo422() {
        ResponseEntity<ApiError> response = handler.handleApiException(new InsufficientFundsException(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().status()).isEqualTo(422);
        assertThat(response.getBody().error()).isEqualTo("Unprocessable Entity");
        assertThat(response.getBody().message()).isEqualTo("Insufficient funds");
        assertThat(response.getBody().path()).isEqualTo("/api/transfers");
        assertThat(response.getBody().timestamp()).isNotNull();
        assertThat(response.getBody().fieldErrors()).isNull();
    }

    @Test
    void mapsAccountNotFoundTo404WithId() {
        UUID id = UUID.randomUUID();

        ResponseEntity<ApiError> response = handler.handleApiException(new AccountNotFoundException(id), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().message()).contains(id.toString());
    }

    @Test
    void mapsNotOwnedTo403() {
        assertThat(handler.handleApiException(new AccountNotOwnedException(), request)
                .getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void mapsClosedAccountTo409() {
        assertThat(handler.handleApiException(new AccountClosedException(UUID.randomUUID()), request)
                .getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void mapsAccountNotEmptyTo409() {
        assertThat(handler.handleApiException(new AccountNotEmptyException(UUID.randomUUID()), request)
                .getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void mapsWriteConflictTo409() {
        assertThat(handler.handleApiException(new WriteConflictException(), request)
                .getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void mapsSelfTransferTo400() {
        assertThat(handler.handleApiException(new SelfTransferException(), request)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void mapsCurrencyMismatchTo400() {
        assertThat(handler.handleApiException(new CurrencyMismatchException(), request)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void mapsEmailAlreadyRegisteredTo409() {
        assertThat(handler.handleApiException(new EmailAlreadyRegisteredException("a@b.com"), request)
                .getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void mapsInvalidCredentialsTo401() {
        assertThat(handler.handleApiException(new InvalidCredentialsException(), request)
                .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void mapsDataIntegrityViolationTo409() {
        ResponseEntity<ApiError> response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("duplicate key value violates unique constraint"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().message())
                .isEqualTo("The request could not be completed due to a conflicting record");
    }

    @Test
    void mapsConcurrencyFailureTo409() {
        ResponseEntity<ApiError> response = handler.handleConcurrencyFailure(
                new ObjectOptimisticLockingFailureException(
                        "com.edwardmagongo.ledgerapi.account.Account", UUID.randomUUID()),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().message())
                .isEqualTo("The account was modified concurrently; please retry the request");
    }

    @Test
    void mapsUnexpectedExceptionTo500WithoutLeakingDetail() {
        ResponseEntity<ApiError> response =
                handler.handleUnexpected(new IllegalStateException("connection string user=admin password=hunter2"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().message()).doesNotContain("hunter2");
    }
}
