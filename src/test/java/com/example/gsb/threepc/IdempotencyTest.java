package com.example.gsb.threepc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 需求 5：重复的预提交与提交请求返回同一结果，且效果只生效一次。
 */
class IdempotencyTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void duplicatePreCommitReturnsSameResultAndAppliesEffectOnce() {
        SimulatedParticipant participant = new SimulatedParticipant("p1", new InMemoryDurableStore());
        participant.canCommit("tx-i1");

        Ack first = participant.preCommit("tx-i1");
        Ack second = participant.preCommit("tx-i1");
        Ack third = participant.preCommit("tx-i1");

        assertThat(first).isEqualTo(Ack.ACK);
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(participant.preCommitCalls()).isEqualTo(3);
        assertThat(participant.prepareEffects("tx-i1")).isEqualTo(1);
        assertThat(participant.state("tx-i1")).isEqualTo(ParticipantState.PRE_COMMITTED);
    }

    @Test
    void duplicateDoCommitReturnsSameResultAndAppliesEffectOnce() {
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore());
        SimulatedParticipant p2 = new SimulatedParticipant("p2", new InMemoryDurableStore());
        ThreePhaseCommitCoordinator coordinator =
                new ThreePhaseCommitCoordinator(new InMemoryDurableStore(), clock);
        coordinator.commit("tx-i2", List.of(p1, p2));

        TransactionOutcome again = p1.doCommit("tx-i2");
        TransactionOutcome onceMore = p1.doCommit("tx-i2");

        assertThat(again).isEqualTo(TransactionOutcome.COMMITTED);
        assertThat(onceMore).isEqualTo(TransactionOutcome.COMMITTED);
        assertThat(p1.commitEffects("tx-i2")).isEqualTo(1);
        assertThat(p1.doCommitCalls()).isEqualTo(3);
    }

    @Test
    void doCommitOnAbortedTransactionReturnsAbortedConsistently() {
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore());
        SimulatedParticipant p2 = new SimulatedParticipant("p2", new InMemoryDurableStore())
                .vote(Vote.NO);
        ThreePhaseCommitCoordinator coordinator =
                new ThreePhaseCommitCoordinator(new InMemoryDurableStore(), clock);
        coordinator.commit("tx-i3", List.of(p1, p2));

        assertThat(p1.doCommit("tx-i3")).isEqualTo(TransactionOutcome.ABORTED);
        assertThat(p1.doCommit("tx-i3")).isEqualTo(TransactionOutcome.ABORTED);
        assertThat(p1.commitEffects("tx-i3")).isZero();
        assertThat(p1.state("tx-i3")).isEqualTo(ParticipantState.ABORTED);
    }

    @Test
    void duplicateCommitCommandReturnsStoredOutcomeWithoutReRunningProtocol() {
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore());
        SimulatedParticipant p2 = new SimulatedParticipant("p2", new InMemoryDurableStore());
        ThreePhaseCommitCoordinator coordinator =
                new ThreePhaseCommitCoordinator(new InMemoryDurableStore(), clock);

        TransactionResult first = coordinator.commit("tx-i4", List.of(p1, p2));
        TransactionResult second = coordinator.commit("tx-i4", List.of(p1, p2));

        assertThat(second.outcome()).isEqualTo(first.outcome());
        assertThat(second.complete()).isTrue();
        assertThat(p1.canCommitCalls()).isEqualTo(1);
        assertThat(p1.preCommitCalls()).isEqualTo(1);
        assertThat(p1.doCommitCalls()).isEqualTo(1);
        assertThat(p1.commitEffects("tx-i4")).isEqualTo(1);
    }

    @Test
    void repeatedRecoverIsIdempotent() {
        InMemoryDurableStore coordinatorStore = new InMemoryDurableStore();
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore());
        SimulatedParticipant p2 = new SimulatedParticipant("p2", new InMemoryDurableStore());
        List<SimulatedParticipant> participants = List.of(p1, p2);
        ThreePhaseCommitCoordinator crashed = new ThreePhaseCommitCoordinator(coordinatorStore, clock,
                record -> {
                    if (record.state() == CoordinatorState.COMMITTING) {
                        throw new SimulatedCrashException();
                    }
                });
        assertThatThrownBy(() -> crashed.commit("tx-i5", List.copyOf(participants)))
                .isInstanceOf(SimulatedCrashException.class);

        ThreePhaseCommitCoordinator restarted = new ThreePhaseCommitCoordinator(coordinatorStore, clock);
        TransactionResult first = restarted.recover("tx-i5", List.copyOf(participants));
        TransactionResult second = restarted.recover("tx-i5", List.copyOf(participants));

        assertThat(first.outcome()).isEqualTo(TransactionOutcome.COMMITTED);
        assertThat(second.outcome()).isEqualTo(TransactionOutcome.COMMITTED);
        assertThat(second.complete()).isTrue();
        for (SimulatedParticipant participant : participants) {
            assertThat(participant.commitEffects("tx-i5")).isEqualTo(1);
            assertThat(participant.doCommitCalls()).isEqualTo(1);
        }
    }
}
