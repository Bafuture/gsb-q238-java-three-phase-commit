package com.example.gsb.threepc;

/**
 * 事务日志事件类型。
 */
public enum LogEventType {
    TX_STARTED,
    VOTE_RECORDED,
    PHASE_ENTERED,
    PRECOMMIT_REPLY_RECORDED,
    DECISION_MADE,
    FINAL_DELIVERY,
    RECOVERY_STARTED,
    TX_COMPLETED
}
