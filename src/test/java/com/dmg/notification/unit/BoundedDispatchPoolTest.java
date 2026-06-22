package com.dmg.notification.unit;

import com.dmg.notification.config.AppProperties;
import com.dmg.notification.worker.BoundedDispatchPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedDispatchPoolTest {

    private BoundedDispatchPool pool;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getDispatch().setGlobalConcurrencyLimit(10);
        props.getDispatch().setPerTenantConcurrencyLimit(3);
        pool = new BoundedDispatchPool(props);
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    @Test
    void executesTasksSuccessfully() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(5);
        UUID tenantId = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            pool.submit(tenantId, () -> {
                counter.incrementAndGet();
                latch.countDown();
            });
        }

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(counter.get()).isEqualTo(5);
    }

    @Test
    void respectsPerTenantFairness() throws InterruptedException {
        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();
        AtomicInteger t1Count = new AtomicInteger();
        AtomicInteger t2Count = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(6);

        for (int i = 0; i < 3; i++) {
            pool.submit(tenant1, () -> { t1Count.incrementAndGet(); latch.countDown(); });
            pool.submit(tenant2, () -> { t2Count.incrementAndGet(); latch.countDown(); });
        }

        latch.await(5, TimeUnit.SECONDS);
        assertThat(t1Count.get()).isEqualTo(3);
        assertThat(t2Count.get()).isEqualTo(3);
    }

    @Test
    void reportsAvailablePermits() {
        assertThat(pool.globalAvailablePermits()).isEqualTo(10);
        UUID tenantId = UUID.randomUUID();
        assertThat(pool.tenantAvailablePermits(tenantId)).isEqualTo(3);
    }
}
