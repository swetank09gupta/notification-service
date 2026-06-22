package com.dmg.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Dispatch dispatch = new Dispatch();
    private final Retry retry = new Retry();
    private final Scheduler scheduler = new Scheduler();
    private final Kafka kafka = new Kafka();
    private final Batch batch = new Batch();

    public Jwt getJwt() { return jwt; }
    public Dispatch getDispatch() { return dispatch; }
    public Retry getRetry() { return retry; }
    public Scheduler getScheduler() { return scheduler; }
    public Kafka getKafka() { return kafka; }
    public Batch getBatch() { return batch; }

    public static class Jwt {
        private String secret;
        private long expirationMs;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getExpirationMs() { return expirationMs; }
        public void setExpirationMs(long expirationMs) { this.expirationMs = expirationMs; }
    }

    public static class Dispatch {
        private int globalConcurrencyLimit = 200;
        private int perTenantConcurrencyLimit = 20;

        public int getGlobalConcurrencyLimit() { return globalConcurrencyLimit; }
        public void setGlobalConcurrencyLimit(int v) { this.globalConcurrencyLimit = v; }
        public int getPerTenantConcurrencyLimit() { return perTenantConcurrencyLimit; }
        public void setPerTenantConcurrencyLimit(int v) { this.perTenantConcurrencyLimit = v; }
    }

    public static class Retry {
        private int maxAttempts = 5;
        private long initialDelaySeconds = 30;
        private int backoffMultiplier = 4;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int v) { this.maxAttempts = v; }
        public long getInitialDelaySeconds() { return initialDelaySeconds; }
        public void setInitialDelaySeconds(long v) { this.initialDelaySeconds = v; }
        public int getBackoffMultiplier() { return backoffMultiplier; }
        public void setBackoffMultiplier(int v) { this.backoffMultiplier = v; }
    }

    public static class Scheduler {
        private long retryPollIntervalMs = 10000;
        private long scheduledPollIntervalMs = 10000;

        public long getRetryPollIntervalMs() { return retryPollIntervalMs; }
        public void setRetryPollIntervalMs(long v) { this.retryPollIntervalMs = v; }
        public long getScheduledPollIntervalMs() { return scheduledPollIntervalMs; }
        public void setScheduledPollIntervalMs(long v) { this.scheduledPollIntervalMs = v; }
    }

    public static class Kafka {
        // Internal topics
        private String topicDispatch = "notification.dispatch";
        private String topicRetry = "notification.retry";
        private String topicDlq = "notification.dlq";
        // External-facing topics
        private String topicRequests = "notification.requests"; // inbound from producers
        private String topicStatus = "notification.status";     // outbound to consumers

        private int dispatchPartitions = 10;
        private int retryPartitions = 10;
        private int dlqPartitions = 1;
        private int requestsPartitions = 10;
        private int statusPartitions = 10;

        public String getTopicDispatch() { return topicDispatch; }
        public void setTopicDispatch(String v) { this.topicDispatch = v; }
        public String getTopicRetry() { return topicRetry; }
        public void setTopicRetry(String v) { this.topicRetry = v; }
        public String getTopicDlq() { return topicDlq; }
        public void setTopicDlq(String v) { this.topicDlq = v; }
        public String getTopicRequests() { return topicRequests; }
        public void setTopicRequests(String v) { this.topicRequests = v; }
        public String getTopicStatus() { return topicStatus; }
        public void setTopicStatus(String v) { this.topicStatus = v; }
        public int getDispatchPartitions() { return dispatchPartitions; }
        public void setDispatchPartitions(int v) { this.dispatchPartitions = v; }
        public int getRetryPartitions() { return retryPartitions; }
        public void setRetryPartitions(int v) { this.retryPartitions = v; }
        public int getDlqPartitions() { return dlqPartitions; }
        public void setDlqPartitions(int v) { this.dlqPartitions = v; }
        public int getRequestsPartitions() { return requestsPartitions; }
        public void setRequestsPartitions(int v) { this.requestsPartitions = v; }
        public int getStatusPartitions() { return statusPartitions; }
        public void setStatusPartitions(int v) { this.statusPartitions = v; }
    }

    public static class Batch {
        private int maxSize = 500;

        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int v) { this.maxSize = v; }
    }
}
