package com.dmg.notification.exception;

import java.util.UUID;

public class DuplicateRequestException extends RuntimeException {
    private final UUID existingRequestId;

    public DuplicateRequestException(String idempotencyKey, UUID existingRequestId) {
        super("Duplicate request for idempotency key: " + idempotencyKey);
        this.existingRequestId = existingRequestId;
    }

    public UUID getExistingRequestId() { return existingRequestId; }
}
