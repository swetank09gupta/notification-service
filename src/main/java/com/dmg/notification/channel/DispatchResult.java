package com.dmg.notification.channel;

public class DispatchResult {

    private final boolean success;
    private final String channelResponse;
    private final String errorMessage;
    private final boolean retryable;

    private DispatchResult(boolean success, String channelResponse, String errorMessage, boolean retryable) {
        this.success = success;
        this.channelResponse = channelResponse;
        this.errorMessage = errorMessage;
        this.retryable = retryable;
    }

    public static DispatchResult success(String response) {
        return new DispatchResult(true, response, null, false);
    }

    public static DispatchResult transientFailure(String error) {
        return new DispatchResult(false, null, error, true);
    }

    public static DispatchResult permanentFailure(String error) {
        return new DispatchResult(false, null, error, false);
    }

    public boolean isSuccess() { return success; }
    public String getChannelResponse() { return channelResponse; }
    public String getErrorMessage() { return errorMessage; }
    public boolean isRetryable() { return retryable; }
}
