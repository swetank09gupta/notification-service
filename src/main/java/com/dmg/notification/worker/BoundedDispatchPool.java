package com.dmg.notification.worker;

import com.dmg.notification.config.AppProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.*;

/**
 * Virtual-thread-based worker pool with two fairness layers:
 *
 *  1. globalSemaphore  — caps total in-flight dispatches across all tenants.
 *  2. per-tenant Semaphore — prevents one noisy tenant from starving others.
 *
 * Virtual threads (Java 21) allow high concurrency without dedicated OS threads.
 * The semaphores are the bounding mechanism; there is no thread-pool queue to overflow.
 */
@Slf4j
@Component
public class BoundedDispatchPool {

    private final Semaphore globalSemaphore;
    private final int perTenantLimit;
    private final ConcurrentHashMap<UUID, Semaphore> tenantSemaphores = new ConcurrentHashMap<>();
    private final ExecutorService virtualExecutor;

    public BoundedDispatchPool(AppProperties props) {
        int globalLimit = props.getDispatch().getGlobalConcurrencyLimit();
        this.perTenantLimit = props.getDispatch().getPerTenantConcurrencyLimit();
        this.globalSemaphore = new Semaphore(globalLimit, true); // fair
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        log.info("BoundedDispatchPool ready — global={} perTenant={}", globalLimit, perTenantLimit);
    }

    /**
     * Submits a dispatch task to a virtual thread.
     * Both semaphores are acquired before the task runs and released on completion.
     * Throws RejectedExecutionException only if the executor itself is shut down.
     */
    public void submit(UUID tenantId, Runnable task) {
        Semaphore tenantSemaphore = tenantSemaphores.computeIfAbsent(
                tenantId, id -> new Semaphore(perTenantLimit, true));

        virtualExecutor.submit(() -> {
            try {
                globalSemaphore.acquire();
                try {
                    tenantSemaphore.acquire();
                    try {
                        task.run();
                    } finally {
                        tenantSemaphore.release();
                    }
                } finally {
                    globalSemaphore.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Dispatch thread interrupted for tenant={}", tenantId);
            }
        });
    }

    public int globalAvailablePermits() {
        return globalSemaphore.availablePermits();
    }

    public int tenantAvailablePermits(UUID tenantId) {
        Semaphore s = tenantSemaphores.get(tenantId);
        return s == null ? perTenantLimit : s.availablePermits();
    }

    @PreDestroy
    public void shutdown() {
        virtualExecutor.shutdown();
        try {
            if (!virtualExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                virtualExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            virtualExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
