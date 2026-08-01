package com.edwardmagongo.ledgerapi.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A client-supplied idempotency key and, once the operation commits, the response it produced.
 *
 * <p>The response body is stored as serialized JSON rather than being re-derived on replay.
 * Responses such as {@code TransferResponse} carry point-in-time balances, so rebuilding one later
 * would return today's balances instead of the balances the original caller saw.
 *
 * <p>{@code user_id} is a plain column, not a {@code @ManyToOne}: nothing here ever navigates to
 * the user, so an association would only add a lazy proxy to no purpose.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdempotentOperation operation;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyKey() {
        // required by JPA
    }

    public IdempotencyKey(String idempotencyKey, UUID userId,
                          IdempotentOperation operation, String requestFingerprint) {
        this.id = UUID.randomUUID();
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.operation = operation;
        this.requestFingerprint = requestFingerprint;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.createdAt = Instant.now();
    }

    public void complete(int responseStatus, String responseBody) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.completedAt = Instant.now();
    }

    public boolean isCompleted() {
        return this.status == IdempotencyStatus.COMPLETED;
    }

    public boolean matches(String fingerprint) {
        return this.requestFingerprint.equals(fingerprint);
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getUserId() { return userId; }
    public IdempotentOperation getOperation() { return operation; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public IdempotencyStatus getStatus() { return status; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
