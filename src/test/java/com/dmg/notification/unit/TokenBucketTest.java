package com.dmg.notification.unit;

import com.dmg.notification.ratelimit.TokenBucket;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    @Test
    void allowsUpToCapacityPerMinute() {
        TokenBucket bucket = new TokenBucket(5, 1000);

        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (bucket.tryConsume()) allowed++;
        }
        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void honoursBothWindowsIndependently() {
        // per-hour limit is tighter than per-minute
        TokenBucket bucket = new TokenBucket(100, 3);

        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse(); // hour limit exhausted
    }

    @Test
    void isThreadSafeUnderConcurrentConsumption() throws InterruptedException {
        int capacity = 50;
        TokenBucket bucket = new TokenBucket(capacity, 10000);
        AtomicInteger allowed = new AtomicInteger();
        int threads = 100;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                if (bucket.tryConsume()) allowed.incrementAndGet();
                latch.countDown();
            });
        }

        latch.await();
        pool.shutdown();

        assertThat(allowed.get()).isEqualTo(capacity);
    }

    @Test
    void refillsTokensOverTime() throws InterruptedException {
        // 60 rpm = 1 token per second; drain fully then verify refill after 1.1s
        int capacity = 60;
        TokenBucket bucket = new TokenBucket(capacity, 100000);

        for (int i = 0; i < capacity; i++) bucket.tryConsume();
        assertThat(bucket.tryConsume()).isFalse(); // drained

        Thread.sleep(1100); // 1.1 s → at 60 rpm (=1/s) should refill 1 token

        assertThat(bucket.tryConsume()).isTrue();
    }
}
