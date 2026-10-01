package com.example.gsb.threepc;

import java.util.List;

/**
 * 三阶段提交（3PC）协调者。
 *
 * <p>协议流程：canCommit → preCommit → doCommit；任一阶段失败即转入中止。
 * 关键不变式：提交决策（进入 COMMITTING）一旦落盘，之后无论崩溃重启
 * 还是参与者超时，事务都只能走向提交；在此之前崩溃则安全中止。
 *
 * <p>所有状态（含事务日志）写入注入的 {@link DurableStore}，
 * 因此“重启”后用同一个 store 构造新实例即可通过 {@link #recover} 续推。
 */
public final class ThreePhaseCommitCoordinator {

    private static final String KEY_PREFIX = "coordinator:tx:";

    private final DurableStore store;
    private final Clock clock;
    private final StatePersistenceListener stateListener;

    public ThreePhaseCommitCoordinator(DurableStore store, Clock clock) {
        this(store, clock, record -> {
        });
    }

    public ThreePhaseCommitCoordinator(DurableStore store, Clock clock,
            StatePersistenceListener stateListener) {
        this.store = store;
        this.clock = clock;
        this.stateListener = stateListener;
    }

    /**
     * 执行完整的三阶段提交流程。
     * 对同一 txId 重复调用：若事务已终结则直接返回已持久化的结果（幂等）。
     */
    public TransactionResult commit(String txId, List<Participant> participants) {
        TransactionRecord existing = load(txId);
        if (existing != null) {
            if (isFinal(existing.state())) {
                return resultOf(existing);
            }
            throw new IllegalStateException(
                    "tx " + txId + " still in flight (" + existing.state() + "); call recover()");
        }

        TransactionRecord record = TransactionRecord.started(txId, participantIds(participants));
        record = log(record, LogEventType.TX_STARTED, "participants=" + record.participantIds());
        record = enterPhase(record, CoordinatorState.CAN_COMMITTING);
        persist(record);

        // 阶段一：canCommit，收集投票；不可达按 NO 处理
        boolean allYes = true;
        for (Participant participant : participants) {
            Vote vote;
            try {
                vote = participant.canCommit(txId);
            } catch (ParticipantUnavailableException e) {
                vote = Vote.NO;
            }
            record = record.withVote(participant.id(), vote);
            record = log(record, LogEventType.VOTE_RECORDED, participant.id() + " -> " + vote);
            persist(record);
            if (vote != Vote.YES) {
                allYes = false;
            }
        }
        if (!allYes) {
            record = decide(record, TransactionOutcome.ABORTED, "at least one participant voted NO");
            return abort(record, participants);
        }

        // 阶段二：preCommit，全部 ACK 才允许进入提交
        record = enterPhase(record, CoordinatorState.PRE_COMMITTING);
        persist(record);
        boolean allAcked = true;
        for (Participant participant : participants) {
            Ack ack;
            try {
                ack = participant.preCommit(txId);
            } catch (ParticipantUnavailableException e) {
                ack = Ack.NACK;
            }
            if (ack == Ack.ACK) {
                record = record.withPreCommitAck(participant.id());
            }
            record = log(record, LogEventType.PRECOMMIT_REPLY_RECORDED, participant.id() + " -> " + ack);
            persist(record);
            if (ack != Ack.ACK) {
                allAcked = false;
            }
        }
        if (!allAcked) {
            record = decide(record, TransactionOutcome.ABORTED, "preCommit was not acked by all participants");
            return abort(record, participants);
        }

        // 阶段三：提交决策落盘后投递 doCommit
        record = decide(record, TransactionOutcome.COMMITTED, "all participants pre-committed");
        record = enterPhase(record, CoordinatorState.COMMITTING);
        persist(record);
        record = deliverFinal(record, participants, TransactionOutcome.COMMITTED);
        record = finalizeIfFullyDelivered(record);
        return resultOf(record);
    }

    /**
     * 重启/故障后的续推：根据持久化的阶段状态继续推进，而不是从头开始。
     * 恢复规则：提交决策已落盘（COMMITTING）→ 继续投递 doCommit；
     * 中止投递中（ABORTING）→ 继续投递 abort；
     * 决策未落盘（CAN_COMMITTING/PRE_COMMITTING）→ 安全中止；
     * 已终结 → 直接返回持久化结果。
     */
    public TransactionResult recover(String txId, List<Participant> participants) {
        TransactionRecord record = load(txId);
        if (record == null) {
            throw new IllegalArgumentException("unknown tx " + txId);
        }
        switch (record.state()) {
            case COMMITTED, ABORTED -> {
                return resultOf(record);
            }
            case COMMITTING -> {
                record = log(record, LogEventType.RECOVERY_STARTED,
                        "commit decision is durable; resume doCommit delivery");
                persist(record);
                record = deliverFinal(record, participants, TransactionOutcome.COMMITTED);
                record = finalizeIfFullyDelivered(record);
                return resultOf(record);
            }
            case ABORTING -> {
                record = log(record, LogEventType.RECOVERY_STARTED,
                        "abort decision is durable; resume abort delivery");
                persist(record);
                record = deliverFinal(record, participants, TransactionOutcome.ABORTED);
                record = finalizeIfFullyDelivered(record);
                return resultOf(record);
            }
            default -> {
                record = log(record, LogEventType.RECOVERY_STARTED,
                        "no durable commit decision; aborting");
                record = decide(record, TransactionOutcome.ABORTED,
                        "coordinator restarted before commit decision");
                return abort(record, participants);
            }
        }
    }

    public TransactionRecord transaction(String txId) {
        return load(txId);
    }

    public List<TransactionLogEntry> log(String txId) {
        TransactionRecord record = load(txId);
        return record == null ? List.of() : record.log();
    }

    private TransactionResult abort(TransactionRecord record, List<Participant> participants) {
        record = enterPhase(record, CoordinatorState.ABORTING);
        persist(record);
        record = deliverFinal(record, participants, TransactionOutcome.ABORTED);
        record = finalizeIfFullyDelivered(record);
        return resultOf(record);
    }

    private TransactionRecord deliverFinal(TransactionRecord record, List<Participant> participants,
            TransactionOutcome decision) {
        for (Participant participant : participants) {
            if (record.finalDelivered().contains(participant.id())) {
                continue;
            }
            try {
                if (decision == TransactionOutcome.COMMITTED) {
                    participant.doCommit(record.txId());
                } else {
                    participant.abort(record.txId());
                }
                record = record.withFinalDelivered(participant.id());
                record = log(record, LogEventType.FINAL_DELIVERY,
                        participant.id() + " <- " + decision + " (delivered)");
            } catch (ParticipantUnavailableException e) {
                record = log(record, LogEventType.FINAL_DELIVERY,
                        participant.id() + " unreachable; will retry on recover");
            }
            persist(record);
        }
        return record;
    }

    private TransactionRecord finalizeIfFullyDelivered(TransactionRecord record) {
        if (!record.pendingParticipants().isEmpty()) {
            return record;
        }
        CoordinatorState terminal = record.decision() == TransactionOutcome.COMMITTED
                ? CoordinatorState.COMMITTED : CoordinatorState.ABORTED;
        record = enterPhase(record, terminal);
        record = log(record, LogEventType.TX_COMPLETED, "outcome=" + record.decision());
        persist(record);
        return record;
    }

    private TransactionRecord decide(TransactionRecord record, TransactionOutcome outcome, String reason) {
        record = record.withDecision(outcome);
        record = log(record, LogEventType.DECISION_MADE, outcome + " (" + reason + ")");
        persist(record);
        return record;
    }

    private TransactionRecord enterPhase(TransactionRecord record, CoordinatorState state) {
        record = record.withState(state);
        return log(record, LogEventType.PHASE_ENTERED, state.name());
    }

    private TransactionRecord log(TransactionRecord record, LogEventType event, String detail) {
        return record.withLog(new TransactionLogEntry(clock.now(), record.txId(), event, detail));
    }

    private void persist(TransactionRecord record) {
        store.put(KEY_PREFIX + record.txId(), record);
        stateListener.onStatePersisted(record);
    }

    private TransactionRecord load(String txId) {
        return store.get(KEY_PREFIX + txId, TransactionRecord.class);
    }

    private TransactionResult resultOf(TransactionRecord record) {
        return new TransactionResult(record.txId(), record.decision(), isFinal(record.state()),
                record.pendingParticipants(), record.log());
    }

    private static boolean isFinal(CoordinatorState state) {
        return state == CoordinatorState.COMMITTED || state == CoordinatorState.ABORTED;
    }

    private static List<String> participantIds(List<Participant> participants) {
        return participants.stream().map(Participant::id).toList();
    }
}
