package com.example.gsb.threepc;

import java.time.Instant;

/**
 * 测试用可控时钟。
 */
final class MutableClock implements Clock {

    private Instant now = Instant.parse("2026-10-01T00:00:00Z");

    @Override
    public Instant now() {
        return now;
    }

    void advanceSeconds(long seconds) {
        now = now.plusSeconds(seconds);
    }
}
