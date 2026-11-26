package io.github.shigella520.linkpeek.server.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One mutex per key, removed again once nobody holds or waits for it, so the map stays
 * proportional to in-flight work instead of growing with every distinct URL ever seen.
 * The holder count is only changed inside {@link ConcurrentHashMap#compute}, which is atomic per key.
 */
final class KeyedLocks {
    private final ConcurrentHashMap<String, Entry> locks = new ConcurrentHashMap<>();

    Handle lock(String key) {
        Entry entry = locks.compute(key, (ignored, existing) -> {
            Entry value = existing == null ? new Entry() : existing;
            value.holders++;
            return value;
        });
        entry.lock.lock();
        return new Handle(key, entry);
    }

    int size() {
        return locks.size();
    }

    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock();
        private int holders;
    }

    final class Handle implements AutoCloseable {
        private final String key;
        private final Entry entry;
        private boolean released;

        private Handle(String key, Entry entry) {
            this.key = key;
            this.entry = entry;
        }

        @Override
        public void close() {
            if (released) {
                return;
            }
            released = true;
            entry.lock.unlock();
            locks.computeIfPresent(key, (ignored, value) -> --value.holders == 0 ? null : value);
        }
    }
}
