package com.example.gsb.tpc;

/** 参与者为单个事务持久化的状态。applyCount 记录事务真正生效的次数（应为 1）。 */
public record ParticipantRecord(String txId, ParticipantStatus status, int applyCount) {
}
