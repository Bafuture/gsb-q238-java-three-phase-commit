package com.example.gsb.threepc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 协调者侧事务的持久化记录（含完整事务日志）。
 * 记录不可变，每次状态迁移整体写回 store，模拟 WAL 落盘。
 */
public record TransactionRecord(
        String txId,
        CoordinatorState state,
        List<String> participantIds,
        Map<String, Vote> votes,
        Set<String> preCommitAcks,
        Set<String> finalDelivered,
        TransactionOutcome decision,
        List<TransactionLogEntry> log) {

    public TransactionRecord {
        participantIds = List.copyOf(participantIds);
        votes = Map.copyOf(votes);
        preCommitAcks = Set.copyOf(preCommitAcks);
        finalDelivered = Set.copyOf(finalDelivered);
        log = List.copyOf(log);
    }

    public static TransactionRecord started(String txId, List<String> participantIds) {
        return new TransactionRecord(txId, CoordinatorState.CAN_COMMITTING,
                participantIds, Map.of(), Set.of(), Set.of(), null, List.of());
    }

    public TransactionRecord withState(CoordinatorState newState) {
        return new TransactionRecord(txId, newState, participantIds, votes,
                preCommitAcks, finalDelivered, decision, log);
    }

    public TransactionRecord withVote(String participantId, Vote vote) {
        Map<String, Vote> copy = new LinkedHashMap<>(votes);
        copy.put(participantId, vote);
        return new TransactionRecord(txId, state, participantIds, copy,
                preCommitAcks, finalDelivered, decision, log);
    }

    public TransactionRecord withPreCommitAck(String participantId) {
        Set<String> copy = new LinkedHashSet<>(preCommitAcks);
        copy.add(participantId);
        return new TransactionRecord(txId, state, participantIds, votes,
                copy, finalDelivered, decision, log);
    }

    public TransactionRecord withFinalDelivered(String participantId) {
        Set<String> copy = new LinkedHashSet<>(finalDelivered);
        copy.add(participantId);
        return new TransactionRecord(txId, state, participantIds, votes,
                preCommitAcks, copy, decision, log);
    }

    public TransactionRecord withDecision(TransactionOutcome newDecision) {
        return new TransactionRecord(txId, state, participantIds, votes,
                preCommitAcks, finalDelivered, newDecision, log);
    }

    public TransactionRecord withLog(TransactionLogEntry entry) {
        List<TransactionLogEntry> copy = new ArrayList<>(log);
        copy.add(entry);
        return new TransactionRecord(txId, state, participantIds, votes,
                preCommitAcks, finalDelivered, decision, copy);
    }

    /** 尚未收到最终决策（doCommit/abort）的参与者。 */
    public Set<String> pendingParticipants() {
        Set<String> pending = new LinkedHashSet<>(participantIds);
        pending.removeAll(finalDelivered);
        return Collections.unmodifiableSet(pending);
    }
}
