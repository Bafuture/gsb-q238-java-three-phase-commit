package com.example.gsb.threepc;

import java.util.List;
import java.util.Set;

/**
 * 一次 commit/recover 调用的结果快照。
 *
 * @param txId                事务 ID
 * @param outcome             最终决策（提交/中止）；决策一旦做出即不可更改
 * @param complete            最终决策是否已送达全部参与者
 * @param pendingParticipants 尚未收到最终决策、待 recover 补齐的参与者
 * @param log                 完整事务日志
 */
public record TransactionResult(
        String txId,
        TransactionOutcome outcome,
        boolean complete,
        Set<String> pendingParticipants,
        List<TransactionLogEntry> log) {

    public TransactionResult {
        pendingParticipants = Set.copyOf(pendingParticipants);
        log = List.copyOf(log);
    }
}
