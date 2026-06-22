package com.dmg.notification.dto.response;

import java.util.List;
import java.util.UUID;

public class BatchSendResponse {

    private final int total;
    private final int accepted;
    private final int rejected;
    private final List<BatchItemResult> results;

    public BatchSendResponse(int total, int accepted, int rejected, List<BatchItemResult> results) {
        this.total = total;
        this.accepted = accepted;
        this.rejected = rejected;
        this.results = results;
    }

    public int getTotal() { return total; }
    public int getAccepted() { return accepted; }
    public int getRejected() { return rejected; }
    public List<BatchItemResult> getResults() { return results; }

    public static class BatchItemResult {
        private final int index;
        private final String idempotencyKey;
        private final String status;   // ACCEPTED | REJECTED
        private final UUID requestId;  // present when accepted
        private final String errorCode;
        private final String errorMessage;

        private BatchItemResult(int index, String idempotencyKey, String status,
                                UUID requestId, String errorCode, String errorMessage) {
            this.index = index;
            this.idempotencyKey = idempotencyKey;
            this.status = status;
            this.requestId = requestId;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public static BatchItemResult accepted(int index, String idempotencyKey, UUID requestId) {
            return new BatchItemResult(index, idempotencyKey, "ACCEPTED", requestId, null, null);
        }

        public static BatchItemResult rejected(int index, String idempotencyKey, String code, String message) {
            return new BatchItemResult(index, idempotencyKey, "REJECTED", null, code, message);
        }

        public int getIndex() { return index; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public String getStatus() { return status; }
        public UUID getRequestId() { return requestId; }
        public String getErrorCode() { return errorCode; }
        public String getErrorMessage() { return errorMessage; }
    }
}
