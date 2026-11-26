package io.github.shigella520.linkpeek.server.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyedLocksTest {
    @Test
    void sameKeyIsMutuallyExclusiveAndMapEmptiesWhenIdle() throws Exception {
        KeyedLocks locks = new KeyedLocks();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 200; i++) {
            pool.submit(() -> {
                start.await();
                try (KeyedLocks.Handle ignored = locks.lock("same")) {
                    maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    Thread.onSpinWait();
                    inside.decrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, maxInside.get());
        assertEquals(0, locks.size());
    }

    @Test
    void distinctKeysDoNotBlockEachOtherAndAreRemovedAfterUse() throws Exception {
        KeyedLocks locks = new KeyedLocks();
        try (KeyedLocks.Handle a = locks.lock("a")) {
            CountDownLatch acquired = new CountDownLatch(1);
            Thread other = new Thread(() -> {
                try (KeyedLocks.Handle b = locks.lock("b")) {
                    acquired.countDown();
                }
            });
            other.start();
            assertTrue(acquired.await(2, TimeUnit.SECONDS));
            other.join();
            assertEquals(1, locks.size());
        }
        assertEquals(0, locks.size());
        for (int i = 0; i < 1_000; i++) {
            try (KeyedLocks.Handle ignored = locks.lock("url-" + i)) {
                assertEquals(1, locks.size());
            }
        }
        assertEquals(0, locks.size());
    }

    @Test
    void reentrantUseAndDoubleCloseAreSafe() {
        KeyedLocks locks = new KeyedLocks();
        KeyedLocks.Handle outer = locks.lock("k");
        try (KeyedLocks.Handle inner = locks.lock("k")) {
            assertEquals(1, locks.size());
        }
        assertEquals(1, locks.size());
        outer.close();
        outer.close();
        assertEquals(0, locks.size());
        assertFalse(Thread.holdsLock(locks));
    }
}
