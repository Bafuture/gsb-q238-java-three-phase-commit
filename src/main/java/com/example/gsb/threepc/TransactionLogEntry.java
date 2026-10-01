package com.example.gsb.threepc;

import java.time.Instant;

/**
 * 一条事务日志：时间戳、事务 ID、事件类型与明细。
 */
public record TransactionLogEntry(Instant timestamp, String txId, LogEventType event, String detail) {
}
