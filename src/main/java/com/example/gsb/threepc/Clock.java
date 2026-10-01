package com.example.gsb.threepc;

import java.time.Instant;

/**
 * 时钟抽象，便于测试注入可控时间（事务日志的时间戳来源于此）。
 */
@FunctionalInterface
public interface Clock {

    Instant now();

    static Clock system() {
        return Instant::now;
    }
}
