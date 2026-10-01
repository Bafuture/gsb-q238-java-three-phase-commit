package com.example.gsb.threepc;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存模拟参与者。协议状态全部写入注入的 {@link DurableStore}，
 * 因此“重启”（用同一个 store 新建实例）后状态不丢失。
 * 通过 vote/failPreCommit/crash 等开关模拟各类故障。
 */
public final class SimulatedParticipant implements Participant {

    private final String id;
    private final DurableStore store;

    private final AtomicInteger canCommitCalls = new AtomicInteger();
    private final AtomicInteger preCommitCalls = new AtomicInteger();
    private final AtomicInteger doCommitCalls = new AtomicInteger();
    private final AtomicInteger abortCalls = new AtomicInteger();

    private volatile Vote configuredVote = Vote.YES;
    private volatile boolean failPreCommit;
    private volatile boolean down;

    public SimulatedParticipant(String id, DurableStore store) {
        this.id = id;
        this.store = store;
    }

    @Override
    public String id() {
        return id;
    }

    public SimulatedParticipant vote(Vote vote) {
        this.configuredVote = vote;
        return this;
    }

    public SimulatedParticipant failPreCommit(boolean fail) {
        this.failPreCommit = fail;
        return this;
    }

    /** 模拟进程崩溃：此后一切协议调用都不可达，直到 {@link #revive()}。 */
    public SimulatedParticipant crash() {
        this.down = true;
        return this;
    }

    public SimulatedParticipant revive() {
        this.down = false;
        return this;
    }

    public int canCommitCalls() {
        return canCommitCalls.get();
    }

    public int preCommitCalls() {
        return preCommitCalls.get();
    }

    public int doCommitCalls() {
        return doCommitCalls.get();
    }

    public int abortCalls() {
        return abortCalls.get();
    }

    public int prepareEffects(String txId) {
        ParticipantRecord record = record(txId);
        return record == null ? 0 : record.prepareEffectsApplied();
    }

    public int commitEffects(String txId) {
        ParticipantRecord record = record(txId);
        return record == null ? 0 : record.commitEffectsApplied();
    }

    @Override
    public Vote canCommit(String txId) {
        checkUp();
        canCommitCalls.incrementAndGet();
        ParticipantRecord record = record(txId);
        if (record != null) {
            switch (record.state()) {
                case READY, PRE_COMMITTED, COMMITTED -> {
                    return Vote.YES;
                }
                case ABORTED -> {
                    return Vote.NO;
                }
                default -> {
                }
            }
        }
        ParticipantState next = configuredVote == Vote.YES
                ? ParticipantState.READY : ParticipantState.ABORTED;
        persist(new ParticipantRecord(txId, next, configuredVote, 0, 0));
        return configuredVote;
    }

    @Override
    public Ack preCommit(String txId) {
        checkUp();
        preCommitCalls.incrementAndGet();
        ParticipantRecord record = record(txId);
        if (record == null) {
            return Ack.NACK;
        }
        switch (record.state()) {
            case PRE_COMMITTED, COMMITTED -> {
                return Ack.ACK;
            }
            case READY -> {
            }
            default -> {
                return Ack.NACK;
            }
        }
        if (failPreCommit) {
            persist(record.withState(ParticipantState.ABORTED));
            return Ack.NACK;
        }
        persist(record.withState(ParticipantState.PRE_COMMITTED).withPrepareEffectApplied());
        return Ack.ACK;
    }

    @Override
    public TransactionOutcome doCommit(String txId) {
        checkUp();
        doCommitCalls.incrementAndGet();
        ParticipantRecord record = record(txId);
        if (record == null) {
            throw new IllegalStateException(id + ": doCommit for unknown tx " + txId);
        }
        switch (record.state()) {
            case COMMITTED -> {
                return TransactionOutcome.COMMITTED;
            }
            case ABORTED -> {
                return TransactionOutcome.ABORTED;
            }
            case PRE_COMMITTED -> {
                persist(record.withState(ParticipantState.COMMITTED).withCommitEffectApplied());
                return TransactionOutcome.COMMITTED;
            }
            default -> throw new IllegalStateException(
                    id + ": doCommit before preCommit, state=" + record.state());
        }
    }

    @Override
    public TransactionOutcome abort(String txId) {
        checkUp();
        abortCalls.incrementAndGet();
        ParticipantRecord record = record(txId);
        if (record == null) {
            persist(new ParticipantRecord(txId, ParticipantState.ABORTED, null, 0, 0));
            return TransactionOutcome.ABORTED;
        }
        if (record.state() == ParticipantState.COMMITTED) {
            return TransactionOutcome.COMMITTED;
        }
        if (record.state() != ParticipantState.ABORTED) {
            persist(record.withState(ParticipantState.ABORTED));
        }
        return TransactionOutcome.ABORTED;
    }

    @Override
    public ParticipantState state(String txId) {
        ParticipantRecord record = record(txId);
        return record == null ? ParticipantState.INIT : record.state();
    }

    @Override
    public TransactionOutcome onCoordinatorTimeout(String txId) {
        checkUp();
        ParticipantRecord record = record(txId);
        if (record == null) {
            return TransactionOutcome.ABORTED;
        }
        switch (record.state()) {
            case PRE_COMMITTED -> {
                persist(record.withState(ParticipantState.COMMITTED).withCommitEffectApplied());
                return TransactionOutcome.COMMITTED;
            }
            case COMMITTED -> {
                return TransactionOutcome.COMMITTED;
            }
            case ABORTED -> {
                return TransactionOutcome.ABORTED;
            }
            default -> {
                persist(record.withState(ParticipantState.ABORTED));
                return TransactionOutcome.ABORTED;
            }
        }
    }

    private void checkUp() {
        if (down) {
            throw new ParticipantUnavailableException("participant " + id + " is unreachable");
        }
    }

    private String key(String txId) {
        return "participant:tx:" + txId;
    }

    private ParticipantRecord record(String txId) {
        return store.get(key(txId), ParticipantRecord.class);
    }

    private void persist(ParticipantRecord record) {
        store.put(key(record.txId()), record);
    }
}
